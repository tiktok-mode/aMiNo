package moe.shizuku.manager.sharingan.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.sharingan.SharinganBubble
import moe.shizuku.manager.terminal.linux.ShizukuExec
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.2.0-alpha — SHARINGAN LIVE SESSION (the Gemini-Like core).
 *
 * ONE TAP on the side eye = this session starts. No record button, no send
 * button, no giant panel — and since v2.2.0-alpha exactly ONE eye on screen
 * (the side bubble carries the whole Amaterasu look; LiveEyeOverlay is
 * DELETED — «الكثير من العيون بدون فائدة، نريد الجانبية التي تشبه Gemini»).
 *
 * Per heard utterance:
 *   1. Echo gate: finals spoken while the assistant ITSELF is speaking (and a
 *      600ms tail window) are IGNORED — the mic hears the TTS on speakerphone
 *      and must never transcribe its own voice (expert-audit CRITICAL).
 *   2. TranscriptCorrector fixes the ASR text (Darija + app names); the raw
 *      stop-command check runs BEFORE the network roundtrip.
 *   3. The row lands in the CHAT (ChatRelay) — nothing is ever silent.
 *   4. LiveVision captures the REAL screen pixels (screencap→PNG→JPEG).
 *      NEW — IDLE WATCH: while LISTENING a fresh frame is captured every ~4s
 *      (md5-gated freshness) so the turn that follows starts from the
 *      FRESHEST possible screen with near-zero latency — the eye truly
 *      watches continuously like Gemini, not only at speech time.
 *   5. LiveBrain sees the frame + reads the corrected command → JSON
 *      {say, actions, done}; say is SPOKEN FIRST (hands-disabled user).
 *      NEW — SESSION MEMORY: decisions ride a persisted multi-turn history
 *      (filesDir/sharingan/live_history.json) — the eye RESUMES its context
 *      after stop/start («لا أريده أن يتوقف ثم ينسى السياق»).
 *   6. Actions execute through the real shell, each with an honest chat row.
 *      Destructive shell patterns are DENIED outright (no hands to confirm).
 *   7. `read` actions pull a FRESH frame (max 8 rounds — the old 3 could
 *      abort mid-GUI-task) → the brain sees the RESULT of its own actions.
 *   8. The ear NEVER stops: barge-in partials kill the TTS mid-sentence.
 *
 * Concurrency: the turn admission is a synchronized check-and-set
 * (turnLock guards turnBusy + pendingUtterance together), so spoken commands
 * are never lost, never duplicated, never resurrected across sessions.
 * A generation token invalidates every async callback on stop().
 */
object LiveSession {

    private const val TAG = "LiveSession"

    // ---------- state ----------
    @Volatile var phase: LivePhase = LivePhase.LISTENING
        private set
    @Volatile var running: Boolean = false
        private set

    private val guard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "live crash", e)
        val app = sessionContext
        if (app != null && running) {
            ChatRelay.note(app, "⚠️ صرة مشكلة في البث: ${(e.message ?: e.javaClass.simpleName).take(140)}")
            AlgerianVoice.speak(AlgerianVoice.Phrases.executedFail("الدور"))
        }
        runCatching { enterListening() }
    }
    private var scope: CoroutineScope? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var ear: LiveEar? = null
    private var turnJob: kotlinx.coroutines.Job? = null

    // Turn state machine — turnBusy + pendingUtterance ALWAYS mutate together
    // under turnLock (expert audit findings: stale reads, lost/duplicated cmds).
    private val turnLock = Any()
    private var turnBusy = false
    private var pendingUtterance: String? = null

    // Session generation — every async callback captures it; stop() bumps it
    // so stale callbacks (TTS init, ear creation) can never resurrect state.
    private val gen = AtomicInteger(0)

    // Echo gate: the assistant must never hear itself (speakerphone loop).
    @Volatile private var lastTtsDoneAt: Long = 0
    private val echoTailMs = 600L

    // IDLE WATCH: the freshest frame captured while LISTENING (Gemini-like
    // continuous watching; the turn consumes it if still fresh).
    @Volatile private var watchedFrame: LiveVision.Frame? = null
    @Volatile private var watchedAt: Long = 0L
    private val watchFreshMs = 8_000L

    @Volatile private var sessionContext: Context? = null

    // ---------- lifecycle ----------

    /** THE ONE BUTTON: eye tap → live starts (or stops if already live). */
    fun toggle(context: Context) {
        if (running) stop(context) else start(context)
    }

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        sessionContext = app
        val myGen = gen.incrementAndGet()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)

        // 1) Mic permission — REQUIRED for «تسمع الكلام مباشرة».
        //    HANDS-FREE FIRST: the user cannot touch a system dialog. The
        //    feature already requires the Shizuku shell, and `pm grant` from
        //    the shell identity is legal — try it; the dialog is the FALLBACK.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            var granted = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (!ShizukuExec.available()) return@withContext false
                    val r = ShizukuExec.oneShot(
                        "pm grant ${app.packageName} android.permission.RECORD_AUDIO", 10_000)
                    r.ok && ContextCompat.checkSelfPermission(
                        app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                }
            }
            if (!granted) {
                ChatRelay.note(app, "👁‍🗨 البث يريد إذن الميكرو — فتحنا صفحة الإذن (آخر مرة تحتاج تلمس فيها).")
                val i = Intent(app, SharinganPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { app.startActivity(i) } catch (t: Throwable) {
                    Log.e(TAG, "permission activity launch failed", t)
                }
                return
            }
        }

        // 2) Shell — frames + actions are shell-born; honest refusal otherwise.
        if (!ShizukuExec.available()) {
            ChatRelay.note(app, "⚠️ ما نقدرش نبدا البث: خدمة الشيل (aMiNo) ماشي مرّوطة — فعّلها من التطبيق أولا.")
            AlgerianVoice.init(app) { AlgerianVoice.speak(AlgerianVoice.Phrases.needShell()) }
            return
        }

        // Honest provider note (audit: silent builtin fallback must be visible).
        if (LiveKeys.overrideWithOpenAiCompat(app) && LiveKeys.creds(app).builtin) {
            ChatRelay.note(app, "ℹ️ طلبت توجيه البث لمفتاحك الشخصي لكنه غير محفوظ — رجعنا للمسار المدمج.")
        }

        running = true
        turnBusy = false
        pendingUtterance = null
        enterListening()

        // SESSION MEMORY: reload what the previous live session remembered —
        // the context is never thrown away by a stop.
        val resumed = try { LiveBrain.historyLoad(app) } catch (_: Throwable) { false }
        if (resumed) {
            ChatRelay.note(app, "👁 رجعنا البث — ذاكرة السياق مستأنفة (${LiveBrain.historySize()} رسالة من الجلسة السابقة).")
        }

        // THE ONE EYE — the side bubble carries the whole live look now
        // (Amaterasu tears + phase breathing). No second window anywhere.
        SharinganBubble.setLiveMode(true)

        ChatRelay.note(app, "👁‍🗨 الشارينغان لايف بدا — قول أي أمر بالدارجة، ونفسي نفّذ وأتحقق. ضغطة على العين توقف البث.")

        // 4) Voice engine → ear → greeting. A 5s watchdog guarantees the ear
        //    exists even if the TTS engine wedges (audit: "deaf session").
        AlgerianVoice.init(app) { ready ->
            if (gen.get() != myGen || !running) return@init
            if (!ready) {
                ChatRelay.note(app, "⚠️ محرك النطق ما خدمش — البث يبقى يسمع وينفذ ويرد في الشات بلا صوت.")
            }
            main.post {
                if (gen.get() != myGen || !running) return@post
                // Utterance finished → back to listening + arm the echo tail.
                AlgerianVoice.onUtteranceDone = {
                    lastTtsDoneAt = System.currentTimeMillis()
                    if (running && phase == LivePhase.SPEAKING) enterListening()
                }
                bootEar(app, myGen)
            }
        }
        main.postDelayed({
            if (gen.get() == myGen && running && ear == null) {
                Log.w(TAG, "TTS init watchdog: booting ear without voice")
                bootEar(app, myGen)
            }
        }, 5_000)

        // Greeting: describe the CURRENT screen in Darija (real vision, not OCR).
        val sc = scope ?: return
        turnJob = sc.launch {
            try {
                greetingTurn(app, resumed)
                drainQueue(app)
            } finally {
                synchronized(turnLock) { turnBusy = false }
                if (running) enterListening()
            }
        }

        // IDLE WATCH loop — continuous Gemini-style watching: while the
        // session is idle (LISTENING, no turn running) it keeps the freshest
        // frame ready, so the next spoken command is answered from a screen
        // captured SECONDS ago (md5-freshness, no burn-in of old frames).
        val watcherGen = myGen
        sc.launch {
            while (running && gen.get() == watcherGen) {
                val idle = synchronized(turnLock) { !turnBusy } &&
                    phase == LivePhase.LISTENING
                if (idle) {
                    try {
                        val f = LiveVision.captureFrame(app)
                        if (f != null && gen.get() == watcherGen) {
                            watchedFrame = f
                            watchedAt = System.currentTimeMillis()
                        }
                    } catch (_: Throwable) {}
                    kotlinx.coroutines.delay(4_000)
                } else {
                    kotlinx.coroutines.delay(1_200)
                }
            }
        }
    }

    private fun bootEar(app: Context, myGen: Int) {
        ear?.stop()
        val newEar = LiveEar(
            app,
            onFinal = { text -> onUtterance(text) },
            onPartialBargeIn = {
                // BARGE-IN: any real partial speech while speaking → stop TTS.
                if (phase == LivePhase.SPEAKING && AlgerianVoice.isSpeaking) {
                    AlgerianVoice.stop()
                    enterListening()
                }
            },
            onFatal = { reason ->
                Log.e(TAG, "ear fatal: $reason")
                val ctx = sessionContext
                if (ctx != null && running) {
                    ChatRelay.note(ctx, "⚠️ الميكرو ما يخدمش ($reason) — البث يبقى لكن بلا سمع.")
                }
            }
        )
        if (gen.get() != myGen) { newEar.stop(); return }
        ear = newEar
        newEar.start()
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        val myGen = gen.incrementAndGet()   // every async callback is now stale
        phase = LivePhase.LISTENING
        main.post {
            ear?.stop(); ear = null
        }
        SharinganBubble.setLiveMode(false)
        AlgerianVoice.stop()
        // Speak the stop ack through the WARM engine (audit: shutdown() raced
        // the synthesis and killed the ack before a word came out).
        AlgerianVoice.speak(AlgerianVoice.Phrases.stopped())
        synchronized(turnLock) {
            turnBusy = false
            pendingUtterance = null   // a queued command of session A never
        }                             // auto-executes in session B
        turnJob?.cancel()
        turnJob = null
        scope?.cancel()
        scope = null
        ChatRelay.note(context, "🛑 وقفنا الشارينغان لايف.")
        sessionContext = null
    }

    fun stopIfRunning(context: Context) = stop(context)

    private fun enterListening() {
        phase = LivePhase.LISTENING
        main.post { SharinganBubble.setPhase(phase) }
    }

    private fun enterPhase(p: LivePhase) {
        phase = p
        main.post { SharinganBubble.setPhase(p) }
    }

    // ---------- turns ----------

    private suspend fun greetingTurn(app: Context, resumed: Boolean) {
        enterPhase(LivePhase.THINKING)
        val frame = LiveVision.captureFrame(app) ?: run {
            ChatRelay.note(app, "⚠️ ما نجملت لقطة الشاشة — تحقق من خدمة الشيل.")
            speakIfRunning(app, AlgerianVoice.Phrases.frameFail())
            enterListening()
            return
        }
        val system = LiveBrain.systemPrompt(app, frame.realW, frame.realH, frame.analysisH)
        val instruction = if (resumed)
            "البث رجع بعد توقف — ذاكرة الجلسة السابقة موجودة في الرسائل السابقة، استعملها مع الأوامر الجديدة. قدّم نفسك بجملة قصيرة بالدارجة وصف وشاشة المستخدم توا (شنو يبان فعلاً). done=true, actions=[]."
        else
            "البث بدا توّا. قدّم نفسك بجملة قصيرة بالدارجة وصف وشاشة المستخدم توا (شنو يبان فعلاً). done=true, actions=[]."
        val decision = LiveBrain.vision(app, system, instruction, "", frame.b64Jpeg)
        val say = decision.say.ifBlank { AlgerianVoice.Phrases.started() }
        ChatRelay.assistant(app, "👁 $say")
        speakIfRunning(app, say)
    }

    /** A final ASR result arrived → full watch-hear-act turn. */
    private fun onUtterance(rawText: String) {
        if (!running) return
        // Ear-level fatal sentinels (mic permission dropped / no STT engine).
        if (rawText.startsWith("\u0000")) {
            val app = sessionContext ?: return
            ChatRelay.note(app, "⚠️ الميكرو ما عادش يخدم — نوقفوا البث. (${rawText.drop(1)})")
            stop(app)
            return
        }
        // ECHO GATE (expert audit CRITICAL #4/#49): finals while the assistant
        // is speaking are its own voice on the speakerphone — never transcribe
        // yourself, never execute your own words. Plus a tail window after.
        if (phase == LivePhase.SPEAKING) return
        if (System.currentTimeMillis() - lastTtsDoneAt < echoTailMs) return

        val app = sessionContext ?: return
        // Race-free admission: under turnLock, either this utterance STARTS a
        // turn or it is safely QUEUED (never dropped, never duplicated).
        val admitted = synchronized(turnLock) {
            if (turnBusy) {
                pendingUtterance = rawText
                false
            } else {
                turnBusy = true
                true
            }
        }
        if (!admitted) return
        val sc = scope ?: run { synchronized(turnLock) { turnBusy = false }; return }
        turnJob = sc.launch {
            try {
                var current = rawText
                while (running) {
                    executeTurn(app, current)
                    if (!running) break
                    val next = synchronized(turnLock) {
                        pendingUtterance.also { pendingUtterance = null }
                    } ?: break
                    current = next
                }
            } finally {
                // Late arrivals between the last drain and release are picked
                // up here — the queue can never strand a spoken command.
                while (running) {
                    val late = synchronized(turnLock) {
                        if (turnBusy && pendingUtterance != null) {
                            pendingUtterance.also { pendingUtterance = null }
                        } else {
                            turnBusy = false
                            null
                        }
                    } ?: break
                    try { executeTurn(app, late) } catch (t: Throwable) {
                        Log.e(TAG, "late turn failed", t)
                    }
                }
                synchronized(turnLock) { turnBusy = false }
                if (running) enterListening()
            }
        }
    }

    /** Drain queued commands after the greeting turn (same loop semantics). */
    private suspend fun drainQueue(app: Context) {
        while (running) {
            val next = synchronized(turnLock) {
                pendingUtterance.also { pendingUtterance = null }
            } ?: break
            executeTurn(app, next)
        }
    }

    /** Speak only if the session is still alive (no zombie speech after stop). */
    private suspend fun speakIfRunning(app: Context, text: String) {
        if (!running) return
        enterPhase(LivePhase.SPEAKING)
        AlgerianVoice.speak(text)
    }

    private suspend fun executeTurn(app: Context, rawVoice: String) {
        enterPhase(LivePhase.THINKING)
        ChatRelay.beginTurn(app)

        // 0) Raw stop-command fast path BEFORE the network roundtrip.
        val stopLike = rawVoice.trim().length <= 18 &&
            Regex("^\\s*(وقف|توقف|التوقف|بريك|بس|كفي|صافي|وقف البث|توقف البث|إيقاف البث|الغاء البث|إلغاء البث|arret|stop)\\s*[.!؟?]*$")
                .containsMatchIn(rawVoice.trim())
        if (stopLike) {
            speakIfRunning(app, AlgerianVoice.Phrases.stopAck())
            stop(app)
            return
        }

        // 1) Correct the transcript (TikTok-class fixes; local fallback offline).
        val (corrected, usedNet) = TranscriptCorrector.correct(app, rawVoice)
        ChatRelay.user(app, corrected, rawVoice, localFix = !usedNet)

        // 2) Watch-hear-act loop (max 8 vision rounds per utterance — GUI
        //    tasks like «افتح... وانتظر... ثم أخبرني» need launch→wait→read→
        //    describe chains; the old 3 aborted them mid-task).
        var executedSummary = ""
        var finalSay: String? = null
        for (round in 1..8) {
            if (!running) return
            enterPhase(LivePhase.THINKING)
            // The idle watch keeps a FRESH frame ready — use it when young
            // (Gemini-like continuity), otherwise capture now.
            val frame = watchedFrame?.takeIf {
                System.currentTimeMillis() - watchedAt < watchFreshMs
            } ?: LiveVision.captureFrame(app)
            if (frame == null) {
                finalSay = AlgerianVoice.Phrases.frameFail()
                ChatRelay.note(app, "⚠️ فشل التقاط الإطار (الشيل طاح؟)")
                speakIfRunning(app, finalSay)
                return
            }
            val system = LiveBrain.systemPrompt(app, frame.realW, frame.realH, frame.analysisH)
            val instruction = if (round == 1)
                "المستخدم قال (مصحح): «$corrected»"
            else
                "واصل المهمة الأصلية: «$corrected» — انظر اللقطة الجديدة بعد التنفيذ وأكمل أو أنهي."
            val decision = LiveBrain.vision(app, system, instruction, executedSummary, frame.b64Jpeg)
            if (!running) return

            // 3) Speak the ack FIRST (the user hears progress, hands-free).
            if (decision.say.isNotBlank()) {
                ChatRelay.assistant(app, decision.say)
                finalSay = decision.say
                speakIfRunning(app, decision.say)
            }

            if (decision.actions.isEmpty() || decision.done) break

            // 4) Execute actions through the REAL shell, honest rows in chat.
            enterPhase(LivePhase.EXECUTING)
            val sb = StringBuilder(executedSummary)
            var needsRead = false
            for (a in decision.actions) {
                if (!running) return
                if (a.t == "stop_live") {
                    // Audit: never keep executing the rest of a stop-turn.
                    speakIfRunning(app, AlgerianVoice.Phrases.stopAck())
                    stop(app)
                    return
                }
                val (name, ok, output) = runAction(app, a, frame)
                ChatRelay.tool(app, name, ok, output)
                sb.appendLine("- $name: ${if (ok) "ok" else "فشل"} ${output.take(120)}")
                if (a.t == "read") needsRead = true
            }
            executedSummary = sb.toString()
            if (!needsRead) break
        }
        if (finalSay.isNullOrBlank() && running) {
            // HONEST fallback — v2.1.0-alpha said the empty «خلصت. توا قولي
            // واش تحب نزيد» here, which looked like nothing happened. Now the
            // user hears the TRUTH: the analysis did not produce a usable
            // answer, and the correction nudge already ran inside the brain.
            finalSay = AlgerianVoice.Phrases.unclear()
            ChatRelay.assistant(app, finalSay)
            speakIfRunning(app, finalSay)
        }
    }

    // Destructive patterns — the model can NEVER run these without hands to
    // confirm; a hands-disabled user would be defenceless (audit HIGH #50).
    private val SHELL_DENY = Regex(
        "(?i)rm\\s+-[rf]|mkfs|dd\\s+if=|pm\\s+(uninstall|clear|disable)|am\\s+force-stop|" +
        "settings\\s+put\\s+secure|flash_|reboot|shutdown|svc\\s+(data|wifi)\\s+(disable|enable)|" +
        "wm\\s+size|cmd\\s+package\\s+uninstall|kill(all)?\\s+-9|chmod\\s+777\\s+/"
    )
    // Secret patterns never relayed to the model/chat (audit #55).
    private val SECRET_REDACT = Regex(
        "(?i)(nvapi-[A-Za-z0-9_\\-]{8,}|authorization:\\s*bearer\\s+\\S+|api[_-]?key\\s*[=:]\\s*\\S+)"
    )

    /**
     * Execute ONE action through the real channel. Returns (name, ok, output).
     * All actions ride ShizukuExec (the proven ADB-shell identity) — the same
     * channel the typed-chat agent uses, zero new privileges.
     */
    private suspend fun runAction(
        app: Context,
        a: LiveBrain.Action,
        frame: LiveVision.Frame
    ): Triple<String, Boolean, String> = withContext(Dispatchers.IO) {
        try {
            when (a.t) {
                "shell" -> {
                    val cmd = a.cmd ?: return@withContext Triple("shell", false, "no cmd")
                    if (SHELL_DENY.containsMatchIn(cmd)) {
                        return@withContext Triple("shell «${cmd.take(50)}»", false,
                            "مرفوض: أمر خطر — البث لا ينفذ أوامر مدمرة بدون تأكيد")
                    }
                    val r = ShizukuExec.oneShot(cmd, 20_000)
                    Triple("shell «${cmd.take(60)}»", r.ok,
                        SECRET_REDACT.replace(r.output, "***").take(600).ifBlank { "rc=${r.rc}" })
                }
                "launch" -> {
                    val pkg = a.pkg ?: return@withContext Triple("launch", false, "no pkg")
                    if (!Regex("[A-Za-z0-9._]+").matches(pkg)) {
                        return@withContext Triple("launch", false, "معرف حزمة غير صالح")
                    }
                    val q = ShizukuExec.shellQuote(pkg)
                    // am start with the package is deterministic; monkey fallback.
                    val r = ShizukuExec.oneShot(
                        "monkey -p $q -c android.intent.category.LAUNCHER 1", 20_000)
                    Triple("launch $pkg", r.ok, r.output.take(200))
                }
                "tap" -> {
                    val x = a.x ?: return@withContext Triple("tap", false, "no x")
                    val y = a.y ?: return@withContext Triple("tap", false, "no y")
                    // Coordinate-space guard (audit #11): if the model already
                    // answered in REAL pixels, pass through — never double-scale.
                    val sx: Int
                    val sy: Int
                    if (x > frame.analysisW || y > frame.analysisH) {
                        sx = x.coerceIn(0, frame.realW - 1)
                        sy = y.coerceIn(0, frame.realH - 1)
                    } else {
                        sx = (x * frame.realW.toFloat() / frame.analysisW).toInt()
                            .coerceIn(0, frame.realW - 1)
                        sy = (y * frame.realH.toFloat() / frame.analysisH).toInt()
                            .coerceIn(0, frame.realH - 1)
                    }
                    val r = ShizukuExec.oneShot("input tap $sx $sy", 10_000)
                    if (r.ok) kotlinx.coroutines.delay(450) // post-act settle (r1421 discipline)
                    Triple("tap ($sx,$sy)", r.ok, r.output.take(200))
                }
                "text" -> {
                    val s = a.s ?: return@withContext Triple("text", false, "no text")
                    val clean = s.replace(Regex("[\\r\\n\\t]"), " ")
                    if (!clean.all { it.code in 32..126 }) {
                        // Audit #53: honest rejection — `input text` mangles
                        // non-ASCII on most ROMs; typing garbage then "ok" is a lie.
                        return@withContext Triple("text", false,
                            "الكتابة غير-اللاتينية (عربية/رموز) غير مدعومة في هذا البث — قل النص ليقول بصوت آخر")
                    }
                    val r = ShizukuExec.oneShot(
                        "input text " + ShizukuExec.shellQuote(clean), 10_000)
                    Triple("text «${clean.take(24)}»", r.ok, r.output.take(200))
                }
                "key" -> {
                    val code = when (a.k?.lowercase()) {
                        "enter" -> 66; "back" -> 4; "home" -> 3
                        "recents" -> 187; "volume_up" -> 24; "volume_down" -> 25
                        else -> -1
                    }
                    if (code < 0) return@withContext Triple("key", false, "unknown key ${a.k}")
                    val r = ShizukuExec.oneShot("input keyevent $code", 10_000)
                    Triple("key ${a.k}", r.ok, r.output.take(120))
                }
                "wait" -> {
                    val ms = (a.ms ?: 800).coerceIn(100, 5000)
                    kotlinx.coroutines.delay(ms.toLong())
                    Triple("wait ${ms}ms", true, "")
                }
                "read" -> Triple("read", true, "(لقطة جديدة في الجولة التالية)")
                else -> Triple(a.t, false, "نوع غير معروف")
            }
        } catch (e: Exception) {
            Triple(a.t, false, "استثناء: ${(e.message ?: e.javaClass.simpleName).take(160)}")
        }
    }
}
