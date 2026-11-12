package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.1.0-alpha — THE LIVE VOICE: Algerian-darija speech out.
 *
 * The gift build's owner is a hands-disabled Android developer: the voice is
 * THE interface. Arabic TTS voices on-device speak standard Arabic phonetics,
 * so the Darija FLAVOR comes from the TEXT itself (phrases below are written
 * the way Algerians actually speak) — that is the honest way to get an
 * Algerian-sounding assistant without a custom voice engine.
 *
 * Engine lifecycle (expert-audit hardened):
 *  - ONE engine kept warm for the whole process lifetime — no 1-2s rebind per
 *    session start/stop; stop() only stops the speech, never the engine.
 *  - Every engine gets a GENERATION tag: a shutdown() of a superseded engine
 *    can never overwrite the `ready` flag of the current one (audit #30).
 *  - onReady is always dispatched through the main handler (audit #29).
 *  - speak() on a not-ready engine fires the done-callback immediately so the
 *    phase machine never wedges on «نقول…» forever (audit #31).
 *  - onStop (engine-side interruption) also fires the done-callback (audit #32).
 *
 * Barge-in: speak() uses QUEUE_FLUSH — any new speech (or LiveEar partial)
 * stops the current utterance instantly, like Gemini Live.
 */
object AlgerianVoice {

    private const val TAG = "AlgerianVoice"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val engineGen = AtomicInteger(0)
    private val speaking = AtomicBoolean(false)
    @Volatile var onUtteranceDone: (() -> Unit)? = null
    @Volatile var onSpeechStart: (() -> Unit)? = null

    val isReady: Boolean get() = ready
    val isSpeaking: Boolean get() = speaking.get()

    fun init(context: Context, onReady: (Boolean) -> Unit) {
        if (ready && tts != null) {
            mainHandler.post { onReady(true) }
            return
        }
        val myGen = engineGen.incrementAndGet()
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        var fired = false
        tts = TextToSpeech(context.applicationContext) { status ->
            // Only the CURRENT engine may write the shared ready flag.
            if (engineGen.get() != myGen) return@TextToSpeech
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    val ar = Locale("ar", "DZ")
                    val res = tts?.setLanguage(ar)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("ar"))
                    }
                    tts?.setSpeechRate(1.05f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speaking.set(true)
                            onSpeechStart?.invoke()
                        }
                        override fun onDone(utteranceId: String?) {
                            speaking.set(false)
                            onUtteranceDone?.invoke()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            speaking.set(false)
                            onUtteranceDone?.invoke()
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            speaking.set(false)
                            onUtteranceDone?.invoke()
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            // Engine-side interruption: still a "done" for the
                            // phase machine (audit #32).
                            speaking.set(false)
                            onUtteranceDone?.invoke()
                        }
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "tts setup failed", t)
                    ready = false
                }
            }
            if (!fired) { fired = true; mainHandler.post { onReady(ready) } }
        }
    }

    /** Speak Darija text NOW (flushes anything queued = barge-in friendly). */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val engine = tts
        if (engine == null || !ready) {
            // No voice yet: fire done immediately so the phase machine never
            // wedges on SPEAKING (audit #31).
            onUtteranceDone?.invoke()
            return
        }
        try {
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "live_" + System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed", t)
            speaking.set(false)
            onUtteranceDone?.invoke()
        }
    }

    /** Stop mid-utterance (barge-in). The ENGINE STAYS WARM (audit #34). */
    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
        speaking.set(false)
    }

    fun shutdown() {
        engineGen.incrementAndGet()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
        speaking.set(false)
    }

    /** The Darija phrases — written exactly how Algerians say them. */
    object Phrases {
        fun started() = "الشارينغان تولى، راني نسمعك توا. قول واش تحب."
        fun stopped() = "وقفنا البث. إي حاجة أخرى، قولي."
        fun needShell() = "ما نقدرش نبدا: خدمة الشيل ماشي مرّوطة. حل التطبيق وفعّل الخدمة أولا."
        fun needMic() = "باش نسمعك، محتاجين إذن الميكرو. رجاءً فعّلوا من الإعدادات."
        fun micDenied() = "رفضتوا إذن الميكرو، ما نجمش نسمع. قولي لي كي تحب تفعّلوا."
        fun shellDownMid() = "خدمة الشيل طاحت، ما نقدرش ننفذ توا."
        fun frameFail() = "ما نجملت الصورة توا، عاود قول الأمر."
        fun thinking() = "راني نشوف توا…"
        fun executedFail(action: String) = "الأمر $action ما مشاش."
        fun stopAck() = "خلاص، نوقف."
        fun hearNothing() = "ما فهمتكش، عاود قولها بشوية."
    }
}
