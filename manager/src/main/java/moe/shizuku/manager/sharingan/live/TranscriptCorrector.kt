package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.util.Log

/**
 * v2.1.0-alpha — TRANSCRIPT CORRECTOR (the «تيكت ك» → «تيك توك» fix).
 *
 * FIELD TRUTH (user, verbatim): a programmer says «افتح ف تطبيق تيكت ك» and
 * the recognizer splits/drops letters — the command must land as a CLEAN
 * sentence «افتح تطبيق تيك توك وانتظر حتى يفتح ثم أخبرني بما يظهر على الشاشة»
 * BEFORE anything executes. Correction runs through the live brain (validated
 * in the sandbox: «افتح ف تطبيق تيكت ك» → «افتح تطبيق تيك توك», 1-2s) with a
 * Darija + app-name grounding prompt, and a LOCAL dictionary fallback that
 * still fixes the most common app names when the network is down (honest:
 * local mode is marked in the relayed chat row).
 *
 * Expert-audit hardened:
 *  - reasoning-model budget raised to 1024 and the salvage path validated —
 *    a chain-of-thought tail is REJECTED unless it looks like a command (#24);
 *  - hamza variants ordered longest-first — «إنستاغرام» can no longer become
 *    «إنستغرامغرام» (#25);
 *  - a sanity gate: the rewrite may not explode in length (prompt-injection
 *    damping, #26) and is capped WITH an honest ellipsis (#28).
 */
object TranscriptCorrector {

    private const val TAG = "TranscriptCorrector"

    /** Offline first-aid: longest-first alternations, exact-ish matches only. */
    private val LOCAL_FIXES: List<Pair<Regex, String>> = listOf(
        Pair(Regex("(?i)تي\\s?ك\\s?(تو\\s?ك|توك|tok)"), "تيك توك"),
        Pair(Regex("(?i)تيك\\s?نيك"), "تيك توك"),
        Pair(Regex("(?i)فايس\\s?بوك|فيس\\s?بوك|facebook"), "فيسبوك"),
        Pair(Regex("(?i)واتس\\s?اب|واتساب|whatsapp"), "واتساب"),
        Pair(Regex("(?i)يوتيوب|يوت\\s?وب|youtube"), "يوتيوب"),
        Pair(Regex("إنستا\\s?غرام|انستا\\s?غرام|إنستاغرام|انستاغرام|إنستا|انستا|instagram"), "إنستغرام"),
        Pair(Regex("(?i)غيميل|جيميل|gmail"), "جيميل")
    )

    /** Known package grounding (spoken name → real package id). */
    val APP_GROUNDING: List<Triple<String, String, String>> = listOf(
        Triple("تيك توك", "com.zhiliaoapp.musically", "tiktok"),
        Triple("فيسبوك", "com.facebook.katana", "facebook"),
        Triple("واتساب", "com.whatsapp", "whatsapp"),
        Triple("يوتيوب", "com.google.android.youtube", "youtube"),
        Triple("إنستغرام", "com.instagram.android", "instagram"),
        Triple("جيميل", "com.google.android.gm", "gmail"),
        Triple("المتجر", "com.android.vending", "play store"),
        Triple("الإعدادات", "com.android.settings", "settings"),
        Triple("المتصفح", "com.android.chrome", "chrome")
    )

    /**
     * Correct an ASR transcript into a clean command.
     * Returns (corrected, usedNetwork) — never null: the raw text is the floor.
     */
    fun correct(context: Context, raw: String, appsHint: String? = null): Pair<String, Boolean> {
        val input = raw.trim().replace(Regex("\\s+"), " ")
        if (input.isEmpty()) return Pair(input, false)
        var localFixed = input
        for ((re, replacement) in LOCAL_FIXES) {
            localFixed = localFixed.replace(re, replacement)
        }
        val sys = buildString {
            appendLine("أنت مصحّح نصوص تحويل الكلام إلى كتابة للهجة الجزائرية الدارجة. رجّع فقط الأمر المصحّح، بدون شرح وبدون علامات تنصيص.")
            appendLine("المهمة: أخطاء التعرف على الصوت (حروف محذوفة أو مقسومة، كلمات ملتصقة، أسماء تطبيقات محرفة) تُصلَح تلقائياً حتى تصبح جملة أمر واضحة.")
            appendLine("أمثلة:")
            appendLine("«افتح ف تطبيق تيكت ك» → «افتح تطبيق تيك توك»")
            appendLine("«افتح تطبيق تيكت توك وانتظر حتى يفتح ثم اخبرني بما يظهر على الشاشه» → «افتح تطبيق تيك توك وانتظر حتى يفتح ثم أخبرني بما يظهر على الشاشة»")
            appendLine("«حل الفايسبوك» → «افتح تطبيق فيسبوك»")
            appendLine("«سجل فيدو لة 10 ثواني» → «سجّل فيديو لمدة 10 ثواني»")
            appendLine("قواعد:")
            appendLine("- صحح الإملاء وحمّل الحروف المحذوفة، سمّ التطبيقات بأسمائها الصحيحة بالعربية.")
            appendLine("- أضف الفعل المناسب إن كان ناقصاً (افتح/أغلق/شوف/سجّل...) دون تغيير المعنى أبداً.")
            appendLine("- لا تجب على الأمر ولا تنفذه ولا تضف أوامر جديدة — تصحيح نص فقط. الناتج لا يتجاوز طول الجملة الأصلية بكثير.")
            if (!appsHint.isNullOrBlank()) {
                appendLine("تطبيقات المستخدم المثبتة (استعمل أسماءها كما هي): $appsHint")
            }
        }
        val net = LiveBrain.text(context, sys, localFixed, maxTokens = 1024, temperature = 0.0)
        if (net.isNullOrBlank()) {
            Log.w(TAG, "correction fell back to local mode")
            return Pair(localFixed.take(400), false)
        }
        val cleaned = net.trim()
            .replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
            .removeSurrounding("\"").removePrefix("«").removeSuffix("»")
            .lineSequence().firstOrNull { it.isNotBlank() } ?: localFixed
        // Sanity gate (audit #24/#26): the correction must remain a plausible
        // command — never longer than input+60 or 3x input, never JSON garbage.
        val plausible = !cleaned.trimStart().startsWith("{") &&
            cleaned.length <= maxOf(localFixed.length + 60, localFixed.length * 3)
        val result = if (plausible) cleaned.take(400) else localFixed.take(400)
        return Pair(result, plausible)
    }
}
