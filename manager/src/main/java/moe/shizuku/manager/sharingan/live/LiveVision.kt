package moe.shizuku.manager.sharingan.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.terminal.linux.ShizukuExec
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * v2.1.0-alpha — SHARINGAN LIVE VISION: the frame pipeline.
 *
 * THE core difference from the old OCR-only recorder (deleted with r1421):
 * the screen is captured as REAL PIXELS (`screencap -p` → PNG → stdout in ONE
 * shell roundtrip via ShizukuExec.oneShotBytes), downscaled + JPEG-encoded
 * IN-APP, and sent to the vision-language model as an actual image. The model
 * SEES the picture exactly like Gemini Live — layout, colors, images, video
 * frames, UI state — nothing is reduced to extracted text first.
 *
 * Expert-audit hardened:
 *  - bitmaps recycled in try/finally — no leak on OOM/decode error paths (#19);
 *  - the PNG magic header is verified before decode (#23);
 *  - devices narrower than 720px are never upscaled (#22);
 *  - the slow per-frame `dumpsys` roundtrip is GONE (~1-2s saved per frame —
 *    the model sees the activity name in the pixels anyway);
 *  - realW/realH come from the CAPTURED BITMAP itself (the only truthful
 *    coordinate base — displayMetrics excludes nav bars, audit #17/#51).
 *
 * Privacy note: a JPEG frame (~30-60KB) leaves the device ONLY when the user
 * speaks and asks something, through the same endpoint the user's chat uses.
 * No frame is ever written to app storage.
 */
object LiveVision {

    private const val TAG = "LiveVision"

    /** Analysis image width (px). The model returns tap coords in THIS space. */
    const val ANALYSIS_W = 720

    data class Frame(
        val b64Jpeg: String,   // base64 JPEG for the VL message
        val analysisW: Int,    // sent dims (model coordinate space)
        val analysisH: Int,
        val realW: Int,        // captured bitmap dims (the real pixel space)
        val realH: Int,
        val md5: String        // real hash of the captured PNG (change detector)
    )

    /** Capture ONE live frame. null = capture failed (shell down / decode failed). */
    suspend fun captureFrame(context: android.content.Context): Frame? = withContext(Dispatchers.IO) {
        if (!ShizukuExec.available()) return@withContext null
        val t0 = System.currentTimeMillis()
        val (rc, png) = ShizukuExec.oneShotBytes("screencap -p", 20_000)
        if (rc != 0 || png.size < 1000 ||
            !(png[0] == 0x89.toByte() && png[1] == 0x50.toByte() &&
                png[2] == 0x4E.toByte() && png[3] == 0x47.toByte())) {
            Log.w(TAG, "screencap failed rc=$rc bytes=${png.size} magic=${png.take(4)}")
            return@withContext null
        }
        val md5 = try {
            MessageDigest.getInstance("MD5").digest(png).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { "" }

        var bmp: Bitmap? = null
        var small: Bitmap? = null
        try {
            bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            if (bmp == null) {
                Log.w(TAG, "decode failed: ${png.size} bytes, md5=$md5")
                return@withContext null
            }
            val realW = bmp.width
            val realH = bmp.height
            val outW = minOf(ANALYSIS_W, realW)
            val outH = (realH.toFloat() * outW / realW).toInt().coerceAtLeast(1)
            small = if (outW == realW) bmp
            else Bitmap.createScaledBitmap(bmp, outW, outH, true)
            val jpeg = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 62, jpeg)
            val b64 = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
            Log.i(TAG, "frame ok ${realW}x${realH}->${outW}x${outH} " +
                "jpeg=${jpeg.size() / 1024}KB dt=${System.currentTimeMillis() - t0}ms")
            Frame(b64, outW, outH, realW, realH, md5)
        } finally {
            try { small?.recycle() } catch (_: Throwable) {}
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }
}
