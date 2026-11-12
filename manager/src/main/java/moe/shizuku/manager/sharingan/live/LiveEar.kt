package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.1.0-alpha — THE LIVE EAR: continuous hands-free listening.
 *
 * Gemini-Live behavior, translated to Android primitives:
 *  - SpeechRecognizer runs in a RESTART LOOP — after every result/error the
 *    next session starts within ~250ms, so the ear is effectively always on.
 *  - Locale chain ar-DZ → ar ONLY (expert audit #23: ever rotating to the
 *    device default silently downgrades recognition to gibberish commands;
 *    the chain never leaves Arabic, and a success re-anchors ar-DZ).
 *  - PARTIAL results are the barge-in signal — gated by a 3-char minimum so
 *    a breath/cough partial cannot cut the assistant mid-word (audit #27).
 *  - Final results flow to [onFinal]; empty/no-match just restarts silently.
 *  - ERROR storms back off exponentially (250ms → 2.5s) instead of hammering
 *    the binder 4×/second on devices without a recognizer (audit #24/#25),
 *    and [onFatal] reports honestly when the ear cannot exist at all.
 *
 * All callbacks arrive on the main thread (SpeechRecognizer requirement).
 */
class LiveEar(
    private val context: Context,
    private val onFinal: (String) -> Unit,
    private val onPartialBargeIn: () -> Unit,
    private val onFatal: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val main = Looper.getMainLooper()
    private var recognizer: SpeechRecognizer? = null
    private val running = AtomicBoolean(false)
    private var consecutiveErrors = 0
    private var localeIndex = 0
    // Arabic only — never rotate to a non-Arabic device default (audit #23).
    private val locales = arrayOf("ar-DZ", "ar")

    companion object {
        private const val TAG = "LiveEar"
        const val FATAL_NO_STT = "\u0000NO_STT"
        const val FATAL_MIC_PERMISSION = "\u0000MIC_PERMISSION"
    }

    private val pending = Runnable { startSession() }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        consecutiveErrors = 0
        localeIndex = 0
        handler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "no recognition service on this device")
                running.set(false)
                onFatal("لا توجد خدمة تعرف كلام في هذا النظام")
                return@post
            }
            startSession()
        }
    }

    fun stop() {
        running.set(false)
        handler.removeCallbacks(pending)
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Throwable) {}
        recognizer = null
    }

    private fun startSession() {
        if (!running.get()) return
        if (Looper.myLooper() != main) { handler.post { startSession() }; return }
        try { recognizer?.destroy() } catch (_: Throwable) {}
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locales[localeIndex])
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locales[localeIndex])
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
                startListening(intent)
            }
            Log.d(TAG, "listening locale=${locales[localeIndex]}")
        } catch (t: Throwable) {
            Log.e(TAG, "createSpeechRecognizer failed", t)
            scheduleRestart(1500)
        }
    }

    /** Exponential backoff: 250ms → 2.5s cap (audit #24 restart storms). */
    private fun backoffMs(): Long =
        (250L * consecutiveErrors).coerceIn(250, 2_500)

    private fun scheduleRestart(delayMs: Long) {
        if (!running.get()) return
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            consecutiveErrors++
            val name = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC_PERMISSION"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NET_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                else -> "ERR_$error"
            }
            Log.w(TAG, "stt error $name (streak=$consecutiveErrors)")
            when {
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(700)
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    running.set(false)
                    try { recognizer?.destroy() } catch (_: Throwable) {}
                    recognizer = null
                    onFinal(FATAL_MIC_PERMISSION)
                }
                // A persistent CLIENT failure with silence around it means the
                // recognizer service is wedged or absent — honest fatal, not a
                // 4-per-second restart storm (audit #24).
                error == SpeechRecognizer.ERROR_CLIENT && consecutiveErrors >= 8 -> {
                    running.set(false)
                    try { recognizer?.destroy() } catch (_: Throwable) {}
                    recognizer = null
                    onFatal("التعرف على الكلام متعطل (${consecutiveErrors} محاولات)")
                }
                // Silence is NORMAL — the user simply wasn't talking.
                else -> scheduleRestart(backoffMs())
            }
            // Long bad patches (Bluetooth headset, noise) rotate WITHIN Arabic
            // only, never to a foreign locale (audit #23).
            if (consecutiveErrors > 20) {
                consecutiveErrors = 0
                localeIndex = (localeIndex + 1) % locales.size
                Log.w(TAG, "stt streak reset; locale→${locales[localeIndex]}")
            }
        }
        override fun onResults(results: android.os.Bundle?) {
            consecutiveErrors = 0
            localeIndex = 0   // a success re-anchors the preferred locale
            val arr = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = arr?.firstOrNull()?.trim().orEmpty()
            // Restart FIRST (the ear never goes dark), then deliver.
            scheduleRestart(250)
            if (text.isNotBlank()) onFinal(text)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val arr = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = arr?.firstOrNull()?.trim().orEmpty()
            // 3-char gate: a breath/cough/TV blip must not barge in (audit #27).
            if (partial.length >= 3) onPartialBargeIn()
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }
}
