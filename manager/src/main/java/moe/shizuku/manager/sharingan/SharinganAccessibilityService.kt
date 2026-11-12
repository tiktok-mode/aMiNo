package moe.shizuku.manager.sharingan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityButtonController
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import moe.shizuku.manager.sharingan.live.LiveSession

/**
 * v2.1.0-alpha — the accessibility service that hosts Sharingan Live.
 *
 * The whole role changed per the user's directive («زر accessibility… يجب ان
 * يكون تشغيل star live… وليس تفعيل وتعطيل الخدمة»):
 *  - on connect: the floating red EYE appears (the one button, always on
 *    screen, draggable, watchdog-protected);
 *  - nav-bar accessibility button tap OR eye tap → the LIVE session toggles
 *    (watch + hear + act) — it is a LIVE switch, not a service toggle;
 *  - on unbind/destroy: live stops, overlays go, memory continuations are
 *    re-armed from SQLite.
 */
class SharinganAccessibilityService : AccessibilityService() {

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        buttonCallback?.let { getAccessibilityButtonController().unregisterAccessibilityButtonCallback(it) }
        val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                // THE SAME ROLE AS THE EYE: tap = start/stop LIVE — not a service toggle.
                LiveSession.toggle(this@SharinganAccessibilityService)
            }
        }
        buttonCallback = cb
        getAccessibilityButtonController()
            .registerAccessibilityButtonCallback(cb, Handler(Looper.getMainLooper()))
        SharinganState.update { it.copy(serviceUp = true) }
        instance = this
        // r1418 — overdue durable continuations resume NOW (unchanged, proven).
        try { moe.shizuku.manager.agent.TaskMemory.rescheduleAll(this) } catch (_: Throwable) {}
        // THE ONE BUTTON: the floating eye appears as soon as the service is up.
        try { SharinganBubble.show(this) } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        SharinganState.update { it.copy(serviceUp = false) }
        if (instance === this) instance = null
        try { LiveSession.stopIfRunning(this) } catch (_: Throwable) {}
        SharinganBubble.hide()
        try {
            buttonCallback?.let { getAccessibilityButtonController().unregisterAccessibilityButtonCallback(it) }
        } catch (_: Throwable) {}
        buttonCallback = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SharinganState.update { it.copy(serviceUp = false) }
        if (instance === this) instance = null
        try { LiveSession.stopIfRunning(this) } catch (_: Throwable) {}
        SharinganBubble.hide()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: SharinganAccessibilityService? = null
            private set
    }
}
