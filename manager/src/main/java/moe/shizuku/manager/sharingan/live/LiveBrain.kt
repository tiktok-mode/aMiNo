package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.2.0-alpha — THE LIVE BRAIN: fixed, remembering, reusable.
 *
 * FIELD-PROVEN CRITICAL FIX (sandbox test test_v22_fix.py against the real
 * NVIDIA endpoint, 2026-11-13): v2.1.0-alpha passed the RAW chat-completions
 * ENVELOPE to parseDecision. The model answers with perfect JSON inside
 * `content` — but inside the envelope that JSON arrives ESCAPED, the balanced
 * brace scan can never rebuild it, so vision() returned say="" + no actions
 * and every spoken command degraded to the empty «خلصت…» chat row with
 * NOTHING executed (the user's exact complaint: «لايف يحول يفهم كلامي لكن
 * ما يطبق الأوامر ولا يتفاعل، فقط يحول الكلام إلى دردشة»). The sandbox run
 * proved it: parse(raw envelope) == None, parse(extractContent(raw)) ==
 * full decision with launch tiktok + wait + read. extractContent() now runs
 * FIRST (content, with the reasoning_content salvage), and parseDecision
 * sees the model's actual text.
 *
 * SESSION MEMORY (the «ينسى السياق» fix): the brain keeps a rolling
 * multi-turn history — every user instruction and every assistant decision
 * (TEXT ONLY, images never stored) — capped and persisted to
 * filesDir/sharingan/live_history.json after every turn and reloaded on
 * start(), so the eye RESUMES its context after stop/start, exactly as the
 * user demanded («لا أريده أن يتوقف ثم ينسى السياق»).
 *
 * REPAIR RETRY: a non-JSON answer gets ONE strict repair nudge inside the
 * same conversation before anything is spoken — an unusable decision can
 * never again surface as a silent empty row.
 *
 * analyzeFrame() exposes the same real-pixel VL path as a plain text answer
 * — it powers the restored FULL screen analysis tool (screen_vision) the
 * user asked to bring back («رجّع تحليل الشاشة الكامل»).
 *
 * Kept from v2.1.0-alpha (expert-audit hardened): per-request callTimeout
 * 45s, 429/5xx retry with 1s/2s backoff, reasoning-model salvage, the
 * max_tokens→max_completion_tokens 400-fallback, fence stripping, brace
 * scanning, "null"-string neutralization.
 */
object LiveBrain {

    private const val TAG = "LiveBrain"

    // Live-dedicated client: the shared chat client has a 120s read window;
    // a live turn must fail fast and retry, not freeze «نشوف…» for minutes.
    private val liveClient: OkHttpClient =
        moe.shizuku.manager.agent.OpenAiCompatProvider.client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    // ---------- session memory (context survives stop/start) ----------
    private const val HISTORY_MAX = 16          // messages (8 user+assistant pairs)
    private const val HISTORY_FILE = "sharingan/live_history.json"
    private val histLock = Any()
    private val history = ArrayList<JSONObject>()

    data class Action(
        val t: String,           // shell | launch | tap | text | key | wait | read | stop_live
        val cmd: String? = null,
        val pkg: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val s: String? = null,
        val k: String? = null,
        val ms: Int? = null
    )

    data class Decision(
        val say: String,
        val actions: List<Action>,
        val done: Boolean,
        val rawValidJson: Boolean
    )

    /** The live system prompt — the eye that watches + hears, speaks Darija. */
    fun systemPrompt(context: android.content.Context, realW: Int, realH: Int,
                     analysisH: Int): String = buildString {
        appendLine("أنت «عين AMINO» — عين الشارينغان في بث مباشر (LIVE) على هاتف المستخدم.")
        appendLine("ترى الشاشة الحقيقية في الصورة المرفقة (بكسل حقيقي: ألوان، صور، فيديو، واجهات)، وتسمع كلام المستخدم باللهجة الجزائرية، وتقدر تنفذ أوامر حقيقية.")
        appendLine("المستخدم يتحكم بالصوت فقط (يديه معطلة) — لا تطلب منه أبداً أن يكتب أو يضغط شيئاً؛ نفّذ أنت كل شيء.")
        appendLine("الرسائل السابقة في هذه المحادثة (إن وُجدت) هي ذاكرة الجلسة الحقيقية — استعملها لتفهم «وينسى السياق» أبداً.")
        appendLine()
        appendLine("قواعد صارمة:")
        appendLine("- كلم المستخدم باللهجة الجزائرية الدارجة بالحروف العربية، قصير ونافع (كلامك يُنطق بصوت TTS): بدون رموز، بدون ماركداون، بدون قوائم.")
        appendLine("- ردك يكون JSON صالح فقط، بدون أي شرح حوله:")
        appendLine("{\"say\": \"الكلام المنطوق بالدارجة\", \"actions\": [...], \"done\": true}")
        appendLine("- أنواع actions المتاحة:")
        appendLine("  {\"t\":\"shell\",\"cmd\":\"...\"} — أمر شل حقيقي (صلاحية shell؛ الأوامر المدمرة تُرفض تلقائياً فلا تحاولها)")
        appendLine("  {\"t\":\"launch\",\"pkg\":\"معرف.الحزمة\"} — فتح تطبيق (المعرف من قائمة تطبيقات المستخدم إن وُجدت، وإلا من معرفتك)")
        appendLine("  {\"t\":\"tap\",\"x\":عدد,\"y\":عدد} — ضغطة بإحداثيات داخل الصورة المعروضة عليك (عرضها ${LiveVision.ANALYSIS_W} وارتفاعها $analysisH)")
        appendLine("  {\"t\":\"text\",\"s\":\"نص لاتيني فقط\"} — كتابة نص لاتيني في الحقل المركّز (العربية غير مدعومة هنا — لا تستعملها)")
        appendLine("  {\"t\":\"key\",\"k\":\"enter|back|home|recents|volume_up|volume_down\"} — زر نظام")
        appendLine("  {\"t\":\"wait\",\"ms\":800} — انتظار قصير")
        appendLine("  {\"t\":\"read\"} — خذ لقطة جديدة وانتقل للجولة التالية (مثلاً بعد فتح تطبيق وتنتهي من التحميل)")
        appendLine("  {\"t\":\"stop_live\"} — إيقاف البث")
        appendLine("- إذا كان الجواب كلاماً فقط بدون تنفيذ: actions=[] و done=true")
        appendLine("- إحداثيات x/y داخل مساحة الصورة المرسلة: 0..${LiveVision.ANALYSIS_W} عرضاً و 0..$analysisH ارتفاعاً (التحجيم يتم تلقائياً عندك — لا تحسبه أنت).")
        appendLine("- لا تخترع شيئاً غير موجود في الصورة. إن كانت الشاشة مقفلة أو ظاهرة شيء آخر قل الحقيقة بالدارجة.")
        appendLine("- بعد تنفيذ سلسلة خطوات وصولاً للهدف → done=true وقل النتيجة الحقيقية كما في اللقطة الأخيرة.")
        appendLine("- إن كان مطلوباً «انتظر حتى يفتح ثم أخبرني بما يظهر»: استعمل launch ثم wait ثم read؛ وفي الجولة التالية صف ما تراه فعلاً في اللقطة الجديدة.")
    }

    /**
     * One vision round: frame + instruction + executed-so-far summary → decision.
     * Multi-turn aware: the session history rides along as text messages and
     * THIS turn's exchange is appended on success (never on failure — a
     * broken turn must not poison the memory).
     * Returns Decision even on protocol hiccups (say = honest error text).
     */
    fun vision(
        context: android.content.Context,
        system: String,
        instruction: String,
        executedSummary: String,
        frameB64: String
    ): Decision {
        val creds = LiveKeys.creds(context)
        return try {
            val userText = buildString {
                appendLine(instruction)
                if (executedSummary.isNotBlank()) {
                    appendLine("ما نُفّذ فعلاً في هذا الدور (نتائج حقيقية):")
                    appendLine(executedSummary)
                    appendLine("اللقطة المرفقة هي الحالة الحالية بعد التنفيذ — واصل أو أنهي بحسب ما تراه.")
                }
            }
            val messages = JSONArray().put(
                JSONObject().put("role", "system").put("content", system))
            synchronized(histLock) { for (m in history) messages.put(m) }
            val userMsg = JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", userText))
                .put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$frameB64"))))
            messages.put(userMsg)

            var decision = requestDecision(creds, messages, 4096)

            // REPAIR RETRY: one strict nudge inside the SAME conversation —
            // a non-JSON answer never becomes a silent empty row again.
            if (!decision.rawValidJson && decision.actions.isEmpty() && decision.say.isBlank()) {
                Log.w(TAG, "unusable decision — one repair retry")
                val repair = JSONArray()
                for (i in 0 until messages.length()) repair.put(messages.optJSONObject(i))
                repair.put(JSONObject().put("role", "assistant")
                    .put("content", "لست متأكداً من الصيغة"))
                repair.put(JSONObject().put("role", "user").put("content",
                    "ردّك السابق لم يكن JSON صالحاً. أعد الإجابة الآن: JSON فقط بالصيغة " +
                    "{\"say\":\"...\",\"actions\":[...],\"done\":true} بدون أي نص آخر."))
                val repaired = requestDecision(creds, repair, 2048)
                if (repaired.rawValidJson || repaired.say.isNotBlank()) decision = repaired
            }

            // MEMORY: remember THIS exchange (text only) on a usable decision.
            if (decision.rawValidJson) {
                remember(context, userText, decision)
            }
            decision
        } catch (e: Exception) {
            Log.e(TAG, "vision failed", e)
            Decision(say = "صرات مشكلة في التحليل، عاود قول الأمر.",
                actions = emptyList(), done = true, rawValidJson = false)
        }
    }

    /** POST + extractContent FIRST (the critical fix) + parse. */
    private fun requestDecision(creds: LiveKeys.Creds, messages: JSONArray,
                                maxTokens: Int): Decision {
        val body = JSONObject()
            .put("model", creds.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        val (code, raw) = post(creds, body)
        if (code !in 200..299) {
            return Decision(
                say = "راني ما نقدرش نتواصل مع العقل الآن (${httpHint(code, raw)})",
                actions = emptyList(), done = true, rawValidJson = false)
        }
        // THE FIX: pull the model's message content out of the envelope FIRST,
        // then parse — never parse the envelope itself. Null content parses as
        // "" → the vision() repair-retry path handles it honestly.
        val content = extractContent(raw) ?: ""
        return parseDecision(content, creds)
    }

    /** Plain text call (transcript correction + short utilities). */
    fun text(context: android.content.Context, system: String, user: String,
             maxTokens: Int = 1024, temperature: Double = 0.0): String? {
        val creds = LiveKeys.creds(context)
        return try {
            val body = JSONObject()
                .put("model", creds.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .put("stream", false)
            val (code, raw) = post(creds, body)
            if (code !in 200..299) {
                Log.w(TAG, "text call HTTP $code")
                return null
            }
            extractContent(raw)
        } catch (e: Exception) {
            Log.w(TAG, "text call failed", e)
            null
        }
    }

    /**
     * FULL SCREEN ANALYSIS on demand (the restored «تحليل الشاشة الكامل»):
     * real pixels → VL model → a plain detailed description. Used by the
     * screen_vision tool in the TYPED chat too — not just live.
     * CRITIC FIX #10 (team B): failures return an HONEST reason (bad key vs
     * busy vs HTTP code) — not one flattened message that makes the agent
     * retry pointlessly against a rejected key.
     */
    fun analyzeFrame(context: android.content.Context, frameB64: String,
                     question: String?): String? {
        val sys = buildString {
            appendLine("أنت محلل شاشة أندرويد. ترى الصورة المرفقة (لقطة شاشة حقيقية بالبكسل).")
            appendLine("صِف بدقة ما تراه: التطبيق/النشاط الظاهر، العناوين والنصوص المقروءة فعلاً، الأزرار والعناصر، حالة الشاشة (مقفلة؟ حوار؟ تشغيل فيديو؟).")
            appendLine("إن سُئلت سؤالاً محدداً أجب عنه من الصورة. إن لم تجرؤ على قراءة شيء قل ذلك بصدق — لا تخترع أبداً.")
            appendLine("الجواب نص عادي مفيد (10 أسطر كحد أقصى)، بدون ماركداون وبدون JSON.")
        }
        val q = question?.takeIf { it.isNotBlank() } ?: "صِف هذه الشاشة بالتفصيل المفيد."
        val creds = LiveKeys.creds(context)
        return try {
            val body = JSONObject()
                .put("model", creds.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", q))
                        .put(JSONObject().put("type", "image_url").put("image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$frameB64"))))))
                .put("temperature", 0.2)
                .put("max_tokens", 2048)
                .put("stream", false)
            val (code, raw) = post(creds, body, attempts = 2)
            if (code !in 200..299) {
                Log.w(TAG, "analyzeFrame HTTP $code")
                return "VISION_ERROR (${httpHint(code, raw)})"
            }
            extractContent(raw)?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "analyzeFrame failed", e)
            null
        }
    }

    // ---------- session memory internals ----------

    private fun remember(context: Context, userText: String, d: Decision) {
        val actionsArr = JSONArray()
        for (a in d.actions) {
            val o = JSONObject().put("t", a.t)
            if (!a.cmd.isNullOrBlank()) o.put("cmd", a.cmd!!.take(160))
            if (!a.pkg.isNullOrBlank()) o.put("pkg", a.pkg!!)
            if (a.x != null) o.put("x", a.x)
            if (a.y != null) o.put("y", a.y)
            if (!a.s.isNullOrBlank()) o.put("s", a.s!!.take(120))
            if (!a.k.isNullOrBlank()) o.put("k", a.k!!)
            if (a.ms != null) o.put("ms", a.ms)
            actionsArr.put(o)
        }
        val compact = JSONObject()
            .put("say", d.say)
            .put("actions", actionsArr)
            .put("done", d.done)
        synchronized(histLock) {
            history.add(JSONObject().put("role", "user").put("content", userText.take(600)))
            history.add(JSONObject().put("role", "assistant")
                .put("content", compact.toString()))
            // pair-aware cap: never open the window on an assistant message
            while (history.size > HISTORY_MAX) {
                history.removeAt(0)
                if (history.isNotEmpty() && history.first().optString("role") == "assistant")
                    history.removeAt(0)
            }
        }
        persist(context)
    }

    /** Persist the session memory — ATOMIC (critic fix #7): tmp+fsync+rename
     * so a crash mid-write can never truncate the memory to nothing. */
    private fun persist(context: Context) {
        try {
            val f = File(context.filesDir, HISTORY_FILE)
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            synchronized(histLock) { for (m in history) arr.put(m) }
            val text = arr.toString()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            java.io.FileOutputStream(tmp).channel.force(true)
            if (!tmp.renameTo(f)) {
                // same-dir rename is POSIX-atomic; if the FS refused it, fall
                // back to a direct write rather than losing the memory
                f.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history persist failed", t)
        }
    }

    /** Reload the session memory on live start (returns true if context resumed).
     * A CORRUPT file (impossible since the atomic persist, but defensive) is
     * renamed aside instead of silently wiping the memory. */
    fun historyLoad(context: Context): Boolean {
        return try {
            val f = File(context.filesDir, HISTORY_FILE)
            if (!f.exists()) return false
            val arr = try { JSONArray(f.readText()) } catch (t: Throwable) {
                Log.w(TAG, "history corrupt — moved aside", t)
                val bad = File(f.parentFile, f.name + ".corrupt")
                try { bad.delete(); f.renameTo(bad) } catch (_: Throwable) {}
                return false
            }
            val loaded = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role")
                val content = o.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank())
                    loaded.add(JSONObject().put("role", role).put("content", content))
            }
            synchronized(histLock) {
                history.clear()
                history.addAll(loaded.takeLast(HISTORY_MAX))
            }
            history.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "history load failed", t)
            false
        }
    }

    /** Count of remembered exchanges (for the honest resume note). */
    fun historySize(): Int = synchronized(histLock) { history.size }

    /** Pull the final answer out of a chat-completions response (reasoning-aware). */
    private fun extractContent(raw: String): String? {
        val root = JSONObject(raw)
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val content = msg?.optString("content", "")?.takeIf {
            it.isNotBlank() && it != "null"
        }
        if (content != null) return content
        // Reasoning models may exhaust the budget on thinking: salvage the last
        // non-blank line of reasoning_content (often carries the final answer).
        val reasoning = msg?.optString("reasoning_content", "").orEmpty()
        return reasoning.lineSequence().lastOrNull { it.isNotBlank() }?.take(300)
    }

    private fun httpHint(code: Int, body: String): String {
        if (code == 400) {
            val serverMsg = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()
            if (!serverMsg.isNullOrBlank()) return serverMsg.take(120)
        }
        return when (code) {
            401, 403 -> "المفتاح مرفوض"
            429 -> "الخدمة مشغولة، عاود بعد شوية"
            else -> "HTTP $code"
        }
    }

    /** POST with retry (429/5xx, 1s/2s backoff) and a max_tokens→max_completion_tokens 400-fallback. */
    private fun post(creds: LiveKeys.Creds, body: JSONObject, attempts: Int = 3): Pair<Int, String> {
        var lastCode = -1
        var lastText = ""
        for (attempt in 0 until attempts) {
            val request = Request.Builder()
                .url("${creds.baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer " + String(creds.key))
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val (code, text) = liveClient.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string().orEmpty())
            }
            if (code in 200..299) return Pair(code, text)
            lastCode = code; lastText = text
            // Audit #4: modern reasoning endpoints reject `max_tokens` — retry
            // ONCE with the renamed field.
            if (code == 400 && text.contains("max_tokens") && body.has("max_tokens") &&
                !body.has("max_completion_tokens")) {
                body.put("max_completion_tokens", body.remove("max_tokens"))
                continue
            }
            if (code == 429 || code in 500..599) {
                try { Thread.sleep(if (attempt == 0) 1000 else 2000) } catch (_: InterruptedException) {}
                continue
            }
            break   // other client errors: no retry
        }
        return Pair(lastCode, lastText)
    }

    /** Robust JSON extraction: regex fence strip + balanced-object candidates. */
    internal fun parseDecision(raw: String, creds: LiveKeys.Creds? = null): Decision {
        val cleaned = raw.trim()
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        // Candidate scan (critic fix #11): STRING-AWARE — braces inside JSON
        // string values (a `say` echoing a JSON example) used to desync the
        // depth counter and destroy the real decision object.
        var obj: JSONObject? = null
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in cleaned.indices) {
            val c = cleaned[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val cand = runCatching { JSONObject(cleaned.substring(start, i + 1)) }.getOrNull()
                        if (cand != null && cand.has("say") || cand != null && cand.has("actions")) {
                            obj = cand
                        }
                        start = -1
                    }
                }
            }
        }
        if (obj == null) {
            // No JSON at all — speak the raw text (still useful voice answer),
            // unless it LOOKS like code/JSON garbage (then stay silent-ish).
            val text = cleaned.take(400)
            val looksLikeCode = text.trimStart().startsWith("{") || text.trimStart().startsWith("```")
            return Decision(say = if (looksLikeCode) "" else text,
                actions = emptyList(), done = true, rawValidJson = false)
        }
        val actions = ArrayList<Action>()
        val arr = obj.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                actions.add(Action(
                    t = a.optString("t", "shell").takeUnless { it == "null" } ?: "shell",
                    cmd = a.optString("cmd", "").takeUnless { it.isBlank() || it == "null" },
                    pkg = a.optString("pkg", "").takeUnless { it.isBlank() || it == "null" },
                    x = a.optInt("x", -1).takeIf { it >= 0 },
                    y = a.optInt("y", -1).takeIf { it >= 0 },
                    s = a.optString("s", "").takeUnless { it.isBlank() || it == "null" },
                    k = a.optString("k", "").takeUnless { it.isBlank() || it == "null" },
                    ms = a.optInt("ms", -1).takeIf { it > 0 }
                ))
            }
        }
        val say = obj.optString("say", "")
            .takeUnless { it.isBlank() || it == "null" }
            ?.trim().orEmpty()
            // Guard: a say that starts with JSON/fences is protocol noise —
            // never read raw JSON aloud (audit #6).
            .takeUnless { it.startsWith("{") || it.startsWith("```") }
            .orEmpty()
        return Decision(
            say = say.take(400),
            actions = actions,
            done = obj.optBoolean("done", actions.isEmpty()),
            rawValidJson = true
        )
    }
}
