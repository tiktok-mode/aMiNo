package moe.shizuku.manager.sharingan.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R

/**
 * v2.1.0-alpha — the FALLBACK permission ask for the live voice (RECORD_AUDIO).
 *
 * The normal path is HANDS-FREE: LiveSession tries `pm grant` through the
 * Shizuku shell first (the user is hands-disabled). This transparent activity
 * exists only when the shell is down. One ask, then auto-resume of the live
 * start flow on grant; on denial the refusal is SPOKEN (audit #36) — never a
 * silent toast-only death.
 */
class SharinganPermissionActivity : androidx.appcompat.app.AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rotation/config change must not re-ask (audit #38).
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            LiveSession.start(this)
            finish()
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), 4101)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4101 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            LiveSession.start(this)
        } else {
            // SPOKEN refusal — a hands-disabled user must never discover the
            // failure by silence (audit #36).
            AlgerianVoice.init(applicationContext) {
                AlgerianVoice.speak(AlgerianVoice.Phrases.micDenied())
            }
            Toast.makeText(this, R.string.sharingan_live_mic_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
