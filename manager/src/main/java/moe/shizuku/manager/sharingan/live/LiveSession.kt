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
 *   1. Echo gate (critic fix #1): finals are dropped while the assistant's
 *      TTS is STILL PLAYING (audio truth: phase==SPEAKING OR isSpeaking) or
 *      within a 900ms tail — the mic never transcribes/executes its own
 *      voice, even when a phase post reordered.
 *   2. TranscriptCorrector fixes the ASR text (Darija + app names); the raw
 *      stop-command check runs BEFORE the network roundtrip.
 *   3. The row lands in the CHAT (ChatRelay) — nothing is ever silent.
 *   4. LiveVision captures the REAL screen pixels (screencap→PNG→JPEG).
 *      IDLE WATCH (Gemini-style continuity): while LISTENING a fresh frame
 *      is captured every ~4s; a turn consumes it ONCE (round 1 only — critic
 *      fix #3: rounds ≥2 always capture fresh so `read` sees the RESULT of
 *      the actions, never the pre-action screen).
 *   5. LiveBrain sees the frame + reads the corrected command → JSON
 *      {say, actions, done}; say is SPOKEN FIRST (hands-disabled user).
 *      SESSION MEMORY: decisions ride a persisted multi-turn history
 *      (filesDir/sharingan/live_history.json) — the eye RESUMES its context
 *      after stop/start («لا أريده أن يتوقف ثم ينسى السياق»).
 *   6. Actions execute through the real shell, each with an honest chat row.
 *      Destructive shell patterns are DENIED outright (no hands to confirm).
 *   7. `read` actions pull a FRESH frame (max 8 rounds per utterance) → the
 *      brain sees the RESULT of its own actions (visual verification).
 *   8. The ear NEVER stops: barge-in partials kill the TTS mid-sentence —
 *      but only after TWO consecutive partials (critic fix #8: a single
 *      spurious echo partial no longer makes the assistant cut ITSELF off).
 *
 * Critic-team fixes applied (v2.2.0-alpha, Task IDs 7-a/7-b):
 *  #1 echo gate on audio truth (not phase posts) + 900ms tail;
 *  #2 the greeting turn ARMS turnBusy — spoken commands queue instead of
 *     racing the greeting (no more concurrent turns / TTS self-cancellation);
 *  #3 watched frame consumed ONCE (round 1) and cleared before any action;
 *  #4 every turn captures the session generation; a stale turn from session
 *     A can never speak/relay/remember into session B;
 *  #5 start() is fully async — the mic `pm grant` can never runBlocking the
 *     MAIN thread into an ANR (a hands-disabled user cannot dismiss one);
 *  #6 pendingUtterance slot → FIFO ArrayDeque (two quick commands are both
 *     kept, never overwritten);
 *  #7 (LiveBrain) history persist is atomic tmp+rename;
 *  #8 barge-in needs two consecutive echo partials;
 *  #9 a late TTS-init callback no longer re-boots a working ear;
 *  #10 the watched frame+timestamp live in ONE immutable holder, cleared on
 *     stop and after consumption.
 *
 * Concurrency: turnBusy + the pending queue ALWAYS mutate together under
 * turnLock; a generation token invalidates every async callback on stop().
 */
object LiveSession {

    private const val TAG = "LiveSession"

    // ---------- state ----------
    @Volatile var phase: LivePhase = LivePhase.LISTENING
        private set
    @Volatile var running: Boolean = false
        private set
    /** Boot window: start() launched its async checks; stop() must still work. */
    @Volatile private var booting = false

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

    // Turn state machine — turnBusy + pendingQueue ALWAYS mutate together
    // under turnLock (critic fix #6: FIFO queue, never an overwrite slot).
    private val turnLock = Any()
    private var turnBusy = false
    private val pendingQueue = ArrayDeque<String>()

    // Session generation — every async callback captures it; stop() bumps it
    // so stale callbacks (TTS init, ear creation) can never resurrect state.
    private val gen = AtomicInteger(0)

    // Echo gate: the assistant must never hear itself (speakerphone loop).
    @Volatile private var lastTtsDoneAt: Long = 0
    private val echoTailMs = 900L

    // Barge-in debounce (critic fix #8): two consecutive partials within
    // 2.5s = real user speech; a single spurious echo partial is ignored.
    private var bargeConsec = 0
    private var bargeLastAt = 0L

    // IDLE WATCH (critic fix #10): ONE immutable holder for frame+timestamp —
    // a reader can never pair an old frame with a new timestamp. Consumed
    // once, cleared on stop, cleared before any action executes.
    private data class Watched(val frame: LiveVision.Frame, val at: Long)
    @Volatile private var watched: Watched? = null
    private val watchFreshMs = 8_000L

    @Volatile private var sessionContext: Context? = null

    // ---------- lifecycle ----------

    /** THE ONE BUTTON: eye tap → live starts (or stops if already live/booting). */
    fun toggle(context: Context) {
        if (running || booting) stop(context) else start(context)
    }

    fun start(context: Context) {
        if (running || booting) return
        val app = context.applicationContext
        sessionContext = app
        val myGen = gen.incrementAndGet()
        booting = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)

        // CRITIC FIX #5: the WHOLE boot is async on IO — the mic `pm grant`
        // roundtrip (up to 10s) can never runBlocking the main thread into an
        // ANR (the user has no hands to dismiss one).
        val bootScope = scope ?: return
        bootScope.launch {
            var bootOk = false
            try {
                // 1) Mic permission — REQUIRED for «تسمع الكلام مباشرة».
                //    HANDS-FREE FIRST: `pm grant` from the shell identity is
                //    legal; the touch dialog is only the FALLBACK.
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    val granted = if (!ShizukuExec.available()) false else {
                        val r = ShizukuExec.oneShot(
                            "pm grant ${app.packageName} android.permission.RECORD_AUDIO", 10_000)
                        r.ok && ContextCompat.checkSelfPermission(
                            app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    }
                    if (gen.get() != myGen) return@launch
                    if (!granted) {
                        ChatRelay.note(app, "👁‍🗨 البث يريد إذن الميكرو — فتحنا صفحة الإذن (آخر مرة تحتاج تلمس فيها).")
                        val i = Intent(app, SharinganPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { app.startActivity(i) } catch (t: Throwable) {
                            Log.e(TAG, "permission activity launch failed", t)
                        }
                        return@launch
                    }
                }

                // 2) Shell — frames + actions are shell-born; honest refusal.
                if (!ShizukuExec.available()) {
                    ChatRelay.note(app, "⚠️ ما نقدرش نبدا البث: خدمة الشيل (aMiNo) ماشي مرّوطة — فعّلها من التطبيق أولا.")
                    AlgerianVoice.init(app) { AlgerianVoice.speak(AlgerianVoice.Phrases.needShell()) }
                    return@launch
                }
                if (gen.get() != myGen) return@launch

                // Honest provider note (audit: silent builtin fallback visible).
                if (LiveKeys.overrideWithOpenAiCompat(app) && LiveKeys.creds(app).builtin) {
                    ChatRelay.note(app, "ℹ️ طلبت توجيه البث لمفتاحك الشخصي لكنه غير محفوظ — رجعنا للمسار المدمج.")
                }
                bootOk = true
            } finally {
                if (!bootOk) {
                    // Failed boot: release everything the tap allocated.
                    booting = false
                    gen.incrementAndGet()          // invalidate every callback
                    main.post { ear?.stop(); ear = null }
                    SharinganBubble.setLiveMode(false)
                    scope?.cancel()
                    scope = null
                    sessionContext = null
                }
            }
            if (gen.get() != myGen || !bootOk) return@launch
            booting = false
            running = true
            synchronized(turnLock) { turnBusy = false; pendingQueue.clear() }
            enterListening()

            // THE ONE EYE — the side bubble carries the whole live look now
            // (Amaterasu tears + phase breathing). No second window anywhere.
            SharinganBubble.setLiveMode(true)

            ChatRelay.note(app, "👁‍🗨 الشارينغان لايف بدا — قول أي أمر بالدارجة، ونفسي نفّذ وأتحقق. ضغطة على العين توقف البث.")

            // 3) Voice engine → ear → greeting. A 5s watchdog guarantees the
            //    ear exists even if the TTS engine wedges (audit: deaf session).
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
                        if (running && phase == LivePhase.SPEAKING &&
                            !AlgerianVoice.isSpeaking) enterListening()
                    }
                    // CRITIC FIX #9: the late TTS callback re-arms callbacks
                    // but must NEVER re-boot a working ear (a 200-500ms deaf
                    // window mid-session used to follow).
                    if (ear == null) bootEar(app, myGen)
                }
            }
            main.postDelayed({
                if (gen.get() == myGen && running && ear == null) {
                    Log.w(TAG, "TTS init watchdog: booting ear without voice")
                    bootEar(app, myGen)
                }
            }, 5_000)

            // Greeting turn — critic fix #2: it ARMS turnBusy like any turn,
            // so commands spoken during the greeting QUEUE (never race it).
            turnJob = bootScope.launch {
                val turnGen = gen.get()
                synchronized(turnLock) { turnBusy = true }
                try {
                    // CRITIC FIX #12: history IO happens here on IO, not on
                    // the main thread that tapped the bubble.
                    val resumed = try { LiveBrain.historyLoad(app) } catch (_: Throwable) { false }
                    if (resumed) {
                        ChatRelay.note(app, "👁 رجعنا البث — ذاكرة السياق مستأنفة (${LiveBrain.historySize()} رسالة من الجلسة السابقة).")
                    }
                    greetingTurn(app, resumed, turnGen)
                    drainQueue(app, turnGen)
                } finally {
                    synchronized(turnLock) { turnBusy = false }
                    // CRITIC FIX #1: leave SPEAKING alone while TTS is still
                    // on-air — onUtteranceDone drives the transition.
                    if (running && gen.get() == turnGen && !AlgerianVoice.isSpeaking) enterListening()
                }
            }

            // IDLE WATCH loop — continuous Gemini-style watching (critic fix
            // #3/#10): the freshest frame is consumed ONCE by the next turn's
            // round 1; the loop keeps it young while LISTENING only.
            val watcherGen = myGen
            launch {
                while (running && gen.get() == watcherGen) {
                    val idle = synchronized(turnLock) { !turnBusy } &&
                        phase == LivePhase.LISTENING
                    if (idle) {
                        try {
                            val f = LiveVision.captureFrame(app)
                            if (f != null && gen.get() == watcherGen) {
                                watched = Watched(f, System.currentTimeMillis())
                            }
                        } catch (_: Throwable) {}
                        kotlinx.coroutines.delay(4_000)
                    } else {
                        kotlinx.coroutines.delay(1_200)
                    }
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
                // BARGE-IN (critic fix #8): a real user interrupt produces
                // SEVERAL partials in quick succession — one spurious echo
                // partial must not make the assistant cut ITSELF off.
                if (phase == LivePhase.SPEAKING && AlgerianVoice.isSpeaking) {
                    val now = System.currentTimeMillis()
                    bargeConsec = if (now - bargeLastAt < 2_500) bargeConsec + 1 else 1
                    bargeLastAt = now
                    if (bargeConsec >= 2) {
                        bargeConsec = 0
                        AlgerianVoice.stop()
                        enterListening()
                    }
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
        // CRITIC FIX #5 companion: stop() works during the boot window too —
        // a tap during the async checks always cancels cleanly.
        if (!running && !booting) return
        running = false
        booting = false
        val myGen = gen.incrementAndGet()   // every async callback is now stale
        phase = LivePhase.LISTENING
        watched = null
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
            pendingQueue.clear()   // a queued command of session A never
        }                          // auto-executes in session B
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

    private suspend fun greetingTurn(app: Context, resumed: Boolean, turnGen: Int) {
        enterPhase(LivePhase.THINKING)
        val frame = LiveVision.captureFrame(app) ?: run {
            ChatRelay.note(app, "⚠️ ما نجملت لقطة الشاشة — تحقق من خدمة الشيل.")
            speakIfRunning(app, AlgerianVoice.Phrases.frameFail(), turnGen)
            enterListening()
            return
        }
        val system = LiveBrain.systemPrompt(app, frame.realW, frame.realH, frame.analysisH)
        val instruction = if (resumed)
            "البث رجع بعد توقف — ذاكرة الجلسة السابقة موجودة في الرسائل السابقة، استعملها مع الأوامر الجديدة. قدّم نفسك بجملة قصيرة بالدارجة وصف وشاشة المستخدم توا (شنو يبان فعلاً). done=true, actions=[]."
        else
            "البث بدا توّا. قدّم نفسك بجملة قصيرة بالدارجة وصف وشاشة المستخدم توا (شنو يبان فعلاً). done=true, actions=[]."
        val decision = LiveBrain.vision(app, system, instruction, "", frame.b64Jpeg)
        // CRITIC FIX #4: a stale session-A greeting never speaks into B.
        if (gen.get() != turnGen) return
        val say = decision.say.ifBlank { AlgerianVoice.Phrases.started() }
        ChatRelay.assistant(app, "👁 $say")
        speakIfRunning(app, say, turnGen)
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
        // ECHO GATE (critic fix #1): audio TRUTH, not phase posts. Finals
        // while the assistant is speaking (phase OR the engine is actually
        // playing) or within the 900ms tail are its own speakerphone voice —
        // dropped, never queued, never executed.
        if (phase == LivePhase.SPEAKING || AlgerianVoice.isSpeaking) return
        if (System.currentTimeMillis() - lastTtsDoneAt < echoTailMs) return

        val app = sessionContext ?: return
        // Race-free admission: under turnLock, either this utterance STARTS a
        // turn or it is safely QUEUED FIFO (critic fix #6: two quick commands
        // are both kept — a slot used to destroy the first).
        val admitted = synchronized(turnLock) {
            if (turnBusy) {
                pendingQueue.addLast(rawText)
                false
            } else {
                turnBusy = true
                true
            }
        }
        if (!admitted) return
        val sc = scope ?: run { synchronized(turnLock) { turnBusy = false }; return }
        // CRITIC FIX #4: the turn captures the session generation — a turn
        // from session A can never resurrect under session B.
        val turnGen = gen.get()
        turnJob = sc.launch {
            try {
                var current = rawText
                while (running && gen.get() == turnGen) {
                    executeTurn(app, current, turnGen)
                    if (!running || gen.get() != turnGen) break
                    val next = synchronized(turnLock) {
                        if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst()
                    } ?: break
                    current = next
                }
            } finally {
                // Late arrivals between the last drain and release are picked
                // up here — the queue can never strand a spoken command.
                while (running && gen.get() == turnGen) {
                    val late = synchronized(turnLock) {
                        if (turnBusy && pendingQueue.isNotEmpty()) {
                            pendingQueue.removeFirst()
                        } else {
                            turnBusy = false
                            null
                        }
                    } ?: break
                    try { executeTurn(app, late, turnGen) } catch (t: Throwable) {
                        Log.e(TAG, "late turn failed", t)
                    }
                }
                synchronized(turnLock) { turnBusy = false }
                // CRITIC FIX #1: only flip to LISTENING when the voice is
                // really done — onUtteranceDone covers the rest.
                if (running && gen.get() == turnGen && !AlgerianVoice.isSpeaking) enterListening()
            }
        }
    }

    /** Drain queued commands after the greeting turn (same loop semantics). */
    private suspend fun drainQueue(app: Context, turnGen: Int) {
        while (running && gen.get() == turnGen) {
            val next = synchronized(turnLock) {
                if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst()
            } ?: break
            executeTurn(app, next, turnGen)
        }
    }

    /** Speak only if the session is still alive (no zombie speech after stop). */
    private suspend fun speakIfRunning(app: Context, text: String, turnGen: Int) {
        if (!running || gen.get() != turnGen) return
        enterPhase(LivePhase.SPEAKING)
        AlgerianVoice.speak(text)
    }

    private suspend fun executeTurn(app: Context, rawVoice: String, turnGen: Int) {
        enterPhase(LivePhase.THINKING)
        ChatRelay.beginTurn(app)

        // 0) Raw stop-command fast path BEFORE the network roundtrip.
        val stopLike = rawVoice.trim().length <= 18 &&
            Regex("^\\s*(وقف|توقف|التوقف|بريك|بس|كفي|صافي|وقف البث|توقف البث|إيقاف البث|الغاء البث|إلغاء البث|arret|stop)\\s*[.!؟?]*$")
                .containsMatchIn(rawVoice.trim())
        if (stopLike) {
            speakIfRunning(app, AlgerianVoice.Phrases.stopAck(), turnGen)
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
            if (!running || gen.get() != turnGen) return
            enterPhase(LivePhase.THINKING)
            // CRITIC FIX #3: the watched frame feeds ROUND 1 ONLY and is
            // consumed once; rounds ≥2 ALWAYS capture fresh — a `read` must
            // see the screen AFTER the actions, never the pre-action frame.
            val frame: LiveVision.Frame? = if (round == 1) {
                val w = watched
                watched = null
                w?.takeIf { System.currentTimeMillis() - it.at < watchFreshMs }?.frame
                    ?: LiveVision.captureFrame(app)
            } else {
                LiveVision.captureFrame(app)
            }
            if (frame == null) {
                finalSay = AlgerianVoice.Phrases.frameFail()
                ChatRelay.note(app, "⚠️ فشل التقاط الإطار (الشيل طاح؟)")
                speakIfRunning(app, finalSay, turnGen)
                return
            }
            val system = LiveBrain.systemPrompt(app, frame.realW, frame.realH, frame.analysisH)
            val instruction = if (round == 1)
                "المستخدم قال (مصحح): «$corrected»"
            else
                "واصل المهمة الأصلية: «$corrected» — انظر اللقطة الجديدة بعد التنفيذ وأكمل أو أنهي."
            val decision = LiveBrain.vision(app, system, instruction, executedSummary, frame.b64Jpeg)
            // CRITIC FIX #4: a decision that lands AFTER stop()/re-start()
            // belongs to a dead session — nothing is spoken, relayed or
            // remembered from it.
            if (!running || gen.get() != turnGen) return

            // 3) Speak the ack FIRST (the user hears progress, hands-free).
            if (decision.say.isNotBlank()) {
                ChatRelay.assistant(app, decision.say)
                finalSay = decision.say
                speakIfRunning(app, decision.say, turnGen)
            }

            if (decision.actions.isEmpty() || decision.done) break

            // 4) Execute actions through the REAL shell, honest rows in chat.
            enterPhase(LivePhase.EXECUTING)
            watched = null   // no action ever runs against a cached screen
            val sb = StringBuilder(executedSummary)
            var needsRead = false
            for (a in decision.actions) {
                if (!running || gen.get() != turnGen) return
                if (a.t == "stop_live") {
                    // Audit: never keep executing the rest of a stop-turn.
                    speakIfRunning(app, AlgerianVoice.Phrases.stopAck(), turnGen)
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
        if (finalSay.isNullOrBlank() && running && gen.get() == turnGen) {
            // HONEST fallback — v2.1.0-alpha said the empty «خلصت. توا قولي
            // واش تحب نزيد» here, which looked like nothing happened. Now the
            // user hears the TRUTH: the analysis did not produce a usable
            // answer, and the repair nudge already ran inside the brain.
            finalSay = AlgerianVoice.Phrases.unclear()
            ChatRelay.assistant(app, finalSay)
            speakIfRunning(app, finalSay, turnGen)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CRITIC FIX #4: cancellation must stay cancellation — swallowing
            // it here resurrected cancelled turns as fake failures.
            throw e
        } catch (e: Exception) {
            Triple(a.t, false, "استثناء: ${(e.message ?: e.javaClass.simpleName).take(160)}")
        }
    }
}
