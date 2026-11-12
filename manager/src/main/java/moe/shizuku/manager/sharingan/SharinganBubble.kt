package moe.shizuku.manager.sharingan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.sharingan.live.LiveSession

/**
 * v2.1.0-alpha — THE ONE BUTTON, REWRITTEN (the gift build).
 *
 * Every complaint from the field is fixed structurally:
 *
 * 1. «الزر لا يتحرك» — the drag is bullet-proof: raw-coordinate deltas,
 *    screen-bounds clamping computed from the VIEW's CURRENT metrics (rotation-
 *    safe, audit #41), haptic confirmation at drag start, and the last position
 *    is SAVED so it reopens exactly where the user parked it.
 *
 * 2. «يختفي خلال ثواني» — the bubble NEVER auto-hides while the service is up.
 *    The watchdog re-asserts it every 4s using isAttachedToWindow — so even a
 *    window silently removed by an OEM ROM is force-re-added (audit #40: a
 *    null-check alone never catches a detached view). rootView is assigned
 *    ONLY after addView succeeds (audit #39: a failed add used to wedge the
 *    object in "visible but not attached" forever).
 *
 * 3. «دور الزر: تشغيل star live» — ONE TAP = LIVE STARTS (watch + hear + act,
 *    voice in Algerian darija). Tapping again stops it. It is NOT a service
 *    enable/disable toggle.
 *
 * 4. Alive feel + accessibility: breathing-scale animation on state change,
 *    clickable + performClick routed through ONE debounced listener (700ms) so
 *    Voice Access / Switch Access can also fire it (audit #42/#43).
 */
object SharinganBubble {

    private const val PREFS = "amino_sharingan_prefs"
    private const val K_POS_X = "bubble_x"
    private const val K_POS_Y = "bubble_y"
    private const val TOGGLE_DEBOUNCE_MS = 700L

    private var windowManager: WindowManager? = null
    private var rootView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var liveMode: Boolean = false
        private set

    private var lastToggleAt = 0L

    /** Watchdog: the eye must NEVER be gone while the service is connected. */
    private val reassertRunnable = object : Runnable {
        override fun run() {
            val svc = SharinganAccessibilityService.instance
            if (svc != null) {
                val v = rootView
                if (v == null) {
                    try { show(svc) } catch (_: Throwable) {}
                } else if (!v.isAttachedToWindow) {
                    // The system silently dropped our window (OEM overlay kill):
                    // force-remove state and re-add (audit #40).
                    hide()
                    try { show(svc) } catch (_: Throwable) {}
                } else {
                    // Rotation-safe clamp: re-anchor the parked bubble onscreen.
                    val cur = params
                    if (cur != null) {
                        val dm = v.resources.displayMetrics
                        val size = (52 * dm.density).toInt()
                        val nx = cur.x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
                        val ny = cur.y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
                        if (nx != cur.x || ny != cur.y) {
                            cur.x = nx; cur.y = ny
                            try { (v.parent as? WindowManager)?.updateViewLayout(v, cur) } catch (_: Throwable) {}
                        }
                    }
                }
                mainHandler.postDelayed(this, 4_000)
            }
            // svc == null → the service died; the loop stops by itself.
        }
    }

    val visible: Boolean get() = rootView != null

    fun show(service: Context) {
        if (rootView != null) return
        val app = service.applicationContext
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dm = service.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val eye = ImageView(service).apply {
            setImageResource(R.drawable.ic_sharingan)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
                setStroke(dp(2), Color.parseColor("#FF8A80"))
            }
            setPadding(dp(9), dp(9), dp(9), dp(9))
            elevation = dp(4).toFloat()
            contentDescription = service.getString(R.string.sharingan_bubble_cd)
            isClickable = true
            isFocusable = false
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(K_POS_X, -1)
        val savedY = prefs.getInt(K_POS_Y, -1)
        val maxRx = (dm.widthPixels - dp(52)).coerceAtLeast(0)
        val maxRy = (dm.heightPixels - dp(52)).coerceAtLeast(0)

        val p = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.coerceIn(0, maxRx) else dm.widthPixels - dp(72)
            y = if (savedY >= 0) savedY.coerceIn(0, maxRy) else (dm.heightPixels * 0.35f).toInt()
        }

        // ONE debounced activation path for touch taps AND a11y-tool clicks.
        fun activate() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) return
            lastToggleAt = now
            try { eye.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Throwable) {}
            LiveSession.toggle(app)
        }
        eye.setOnClickListener { activate() }

        // Touch: drag with clamped bounds; tap = LIVE toggle (via performClick);
        // long-press = hint.
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var longPress: Runnable? = null

        eye.setOnTouchListener(object : View.OnTouchListener {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val cur = params ?: return true
                // CURRENT metrics (rotation-safe) — audit #41.
                val dmNow = v.resources.displayMetrics
                val size = (52 * dmNow.density).toInt()
                val maxX = (dmNow.widthPixels - size).coerceAtLeast(0)
                val maxY = (dmNow.heightPixels - size).coerceAtLeast(0)
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = cur.x; startY = cur.y
                        dragging = false
                        longPress = Runnable {
                            if (!dragging && rootView != null) {
                                Toast.makeText(app,
                                    app.getString(R.string.sharingan_bubble_hint),
                                    Toast.LENGTH_LONG).show()
                            }
                        }.also { v.postDelayed(it, 550) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                            dragging = true
                            longPress?.let { v.removeCallbacks(it) }; longPress = null
                            try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Throwable) {}
                        }
                        if (dragging) {
                            cur.x = (startX + dx).coerceIn(0, maxX)
                            cur.y = (startY + dy).coerceIn(0, maxY)
                            try { wm.updateViewLayout(v, cur) } catch (_: Throwable) {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPress?.let { v.removeCallbacks(it) }; longPress = null
                        if (e.action == MotionEvent.ACTION_UP && !dragging) {
                            v.performClick()   // routes through the debounced listener
                        }
                        if (e.action == MotionEvent.ACTION_UP && dragging) {
                            // Park position persists across service restarts.
                            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putInt(K_POS_X, cur.x).putInt(K_POS_Y, cur.y).apply()
                        }
                    }
                }
                return true
            }
        })

        // AUDIT #39: attach FIRST, then publish state — a failed addView can
        // never wedge the object into "visible but not attached".
        try {
            wm.addView(eye, p)
        } catch (t: Throwable) {
            Log.e("SharinganBubble", "addView failed", t)
            return
        }
        rootView = eye
        params = p
        windowManager = wm
        applyLiveVisual(eye, dp(2))
        // Watchdog armed — the eye survives even OEM overlay removals.
        mainHandler.removeCallbacks(reassertRunnable)
        mainHandler.postDelayed(reassertRunnable, 4_000)
    }

    /** Idle: red ready eye. Live: deep red + larger + hot ring. */
    private fun applyLiveVisual(eye: ImageView, strokeDpPx: Int) {
        if (liveMode) {
            (eye.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#B71C1C"))
                setStroke(strokeDpPx * 2, Color.parseColor("#FF1744"))
            }
            eye.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180).start()
        } else {
            (eye.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#E53935"))
                setStroke(strokeDpPx, Color.parseColor("#FF8A80"))
            }
            eye.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    /** Called by LiveSession on start/stop — the eye visibly changes state. */
    fun setLiveMode(value: Boolean) {
        liveMode = value
        val v = rootView ?: return
        val density = v.resources.displayMetrics.density
        mainHandler.post { applyLiveVisual(v, (2 * density).toInt()) }
    }

    fun hide() {
        val v = rootView ?: return
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        rootView = null
        params = null
        windowManager = null
    }
}
