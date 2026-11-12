package moe.shizuku.manager.sharingan.live

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v2.1.0-alpha — THE LIVE BRAIN: multimodal chat against the configured
 * vision endpoint ([LiveKeys]).
 *
 * Validated against the real NVIDIA integrate endpoint before shipping
 * (scripts/test_live_pipeline.py): image_url with a base64 data URI, strict
 * JSON answer, Algerian-darija `say`, executable `actions`.
 *
 * Expert-audit hardened:
 *  - per-request callTimeout(45s) — a wedged endpoint can never freeze a turn
 *    for the shared client's 120s read window (audit #8/#27);
 *  - 429/5xx retried with 1s/2s backoff instead of killing the turn (audit #2);
 *  - reasoning models: empty `content` is salvaged from `reasoning_content`'s
 *    last JSON-looking object (audit #3), and a 400 that names `max_tokens`
 *    is retried once with `max_completion_tokens` (audit #4);
 *  - `say`/`cmd` "null"-string and brace-contaminated text are neutralized
 *    (audit #5/#6) before anything is spoken or executed;
 *  - fence stripping is regex-based, tolerant of ```JSON / ```text (audit #7);
 *  - the prompt carries the CONCRETE analysis height and the volume keys.
 */
object LiveBrain {

    private const val TAG = "LiveBrain"

    // Live-dedicated client: the shared chat client has a 120s read window;
    // a live turn must fail fast and retry, not freeze «نشوف…» for minutes.
    private val liveClient: OkHttpClient =
        moe.shizuku.manager.agent.OpenAiCompatProvider.client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

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
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", userText))
                    .put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$frameB64")))))
            val body = JSONObject()
                .put("model", creds.model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 2048)
                .put("stream", false)
            val (code, text) = post(creds, body)
            if (code !in 200..299) {
                return Decision(
                    say = "راني ما نقدرش نتواصل مع العقل الآن (${httpHint(code, text)})",
                    actions = emptyList(), done = true, rawValidJson = false)
            }
            parseDecision(text, creds)
        } catch (e: Exception) {
            Log.e(TAG, "vision failed", e)
            Decision(say = "صرات مشكلة في التحليل، عاود قول الأمر.",
                actions = emptyList(), done = true, rawValidJson = false)
        }
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
    private fun post(creds: LiveKeys.Creds, body: JSONObject): Pair<Int, String> {
        var lastCode = -1
        var lastText = ""
        for (attempt in 0 until 3) {
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
        // Candidate scan: the LAST well-formed {...} object wins (audit #6 —
        // prose may carry multiple braces).
        var obj: JSONObject? = null
        var start = -1
        var depth = 0
        for (i in cleaned.indices) {
            val c = cleaned[i]
            if (c == '{') { if (depth == 0) start = i; depth++ }
            else if (c == '}') {
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
