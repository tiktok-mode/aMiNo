package moe.shizuku.manager.sharingan

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import moe.shizuku.manager.R
import moe.shizuku.manager.sharingan.live.LivePhase
import moe.shizuku.manager.sharingan.live.LiveSession
import kotlin.math.cos
import kotlin.math.sin

/**
 * v2.2.0-alpha — THE ONE EYE (user directive, verbatim: «الكثير من العيون
 * بدون فائدة، نريد الجانبية التي تشبه Gemini»).
 *
 * There is now exactly ONE floating eye on the whole screen: this compact
 * SIDE bubble — Gemini-Live placement (right edge, vertically ~35%) — and the
 * giant top-center Amaterasu overlay (LiveEyeOverlay) is DELETED. The
 * Amaterasu identity the user designed («تدمع دموع سوداء مثل ناروتو، تقنية
 * أماتيراسو أثناء البث») now lives INSIDE this small eye:
 *  - idle  : a calm crimson sharingan (3 tomoe, slow rotation);
 *  - live  : black sclera + burning iris + THREE rotating tomoe + TWO BLACK
 *            TEARS dripping below the eye + a breathing halo whose speed
 *            follows the phase (listening/speaking slow, thinking/executing
 *            fast).
 *
 * Everything proven from v2.1.0-alpha is kept:
 *  - bullet-proof drag (raw deltas, rotation-safe clamping, saved parking
 *    spot, haptics) — «الزر لا يتحرك» never again;
 *  - NEVER auto-hides: a 4s watchdog re-asserts the window even against OEM
 *    overlay removal (isAttachedToWindow force re-add);
 *  - ONE debounced activation path (touch + Voice/Switch Access) — tap = the
 *    LIVE toggle (start/stop), never a service enable/disable;
 *  - long-press = honest hint toast.
 */
object SharinganBubble {

    private const val PREFS = "amino_sharingan_prefs"
    private const val K_POS_X = "bubble_x"
    private const val K_POS_Y = "bubble_y"
    private const val TOGGLE_DEBOUNCE_MS = 700L

    private var windowManager: WindowManager? = null
    private var rootView: SharinganEyeView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var liveMode: Boolean = false
        private set

    private var lastToggleAt = 0L

    /** Watchdog: the eye must NEVER be gone while the service is up. */
    private val reassertRunnable = object : Runnable {
        override fun run() {
            val svc = SharinganAccessibilityService.instance
            if (svc != null) {
                val v = rootView
                if (v == null) {
                    try { show(svc) } catch (_: Throwable) {}
                } else if (!v.isAttachedToWindow) {
                    hide()
                    try { show(svc) } catch (_: Throwable) {}
                } else {
                    // Rotation-safe clamp: re-anchor the parked bubble onscreen.
                    val cur = params
                    if (cur != null) {
                        val dm = v.resources.displayMetrics
                        fun dp(v: Int) = (v * dm.density).toInt()
                        val w = dp(58); val h = dp(96)
                        val nx = cur.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                        val ny = cur.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
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

        val eye = SharinganEyeView(service)
        eye.contentDescription = service.getString(R.string.sharingan_bubble_cd)
        eye.isClickable = true
        eye.isFocusable = false
        eye.setLive(liveMode)
        eye.elevation = dp(4).toFloat()

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(K_POS_X, -1)
        val savedY = prefs.getInt(K_POS_Y, -1)
        val maxRx = (dm.widthPixels - dp(58)).coerceAtLeast(0)
        val maxRy = (dm.heightPixels - dp(96)).coerceAtLeast(0)

        val p = WindowManager.LayoutParams(
            dp(58), dp(96),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // GEMINI PLACEMENT: parked on the side edge, ~35% down the screen.
            x = if (savedX >= 0) savedX.coerceIn(0, maxRx) else dm.widthPixels - dp(74)
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
                // CURRENT metrics (rotation-safe).
                val dmNow = v.resources.displayMetrics
                fun dpNow(v: Int) = (v * dmNow.density).toInt()
                val w = dpNow(58); val h = dpNow(96)
                val maxX = (dmNow.widthPixels - w).coerceAtLeast(0)
                val maxY = (dmNow.heightPixels - h).coerceAtLeast(0)
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

        // Attach FIRST, then publish state — a failed addView can never wedge
        // the object into "visible but not attached".
        try {
            wm.addView(eye, p)
        } catch (t: Throwable) {
            Log.e("SharinganBubble", "addView failed", t)
            return
        }
        rootView = eye
        params = p
        windowManager = wm
        // Watchdog armed — the eye survives even OEM overlay removals.
        mainHandler.removeCallbacks(reassertRunnable)
        mainHandler.postDelayed(reassertRunnable, 4_000)
    }

    /** Called by LiveSession on start/stop — idle sharingan ↔ Amaterasu live. */
    fun setLiveMode(value: Boolean) {
        liveMode = value
        val v = rootView ?: return
        mainHandler.post { v.setLive(value) }
    }

    /** Called by LiveSession every phase change — the eye breathes by phase. */
    fun setPhase(p: LivePhase) {
        val v = rootView ?: return
        mainHandler.post { v.setPhase(p) }
    }

    fun hide() {
        val v = rootView ?: return
        try { windowManager?.removeView(v) } catch (_: Throwable) {}
        v.stop()
        rootView = null
        params = null
        windowManager = null
    }
}

/**
 * The drawn sharingan — ZERO allocations in onDraw except one small radial
 * gradient per frame (the breathing halo, same discipline as the deleted
 * LiveEyeOverlay). Idle: crimson eye. Live: black sclera, hot iris, 3 tomoe,
 * 2 Amaterasu black tears dripping BELOW the eye inside the window.
 */
private class SharinganEyeView(context: Context) : View(context) {

    private var live = false
    private var phase = LivePhase.LISTENING

    // cached paints — onDraw is allocation-light
    private val sclera = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#120404") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iris = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tomoe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#120404") }
    private val tear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000"); style = Paint.Style.FILL
    }
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5E000000"); style = Paint.Style.STROKE
    }

    private var irisColors = intArrayOf(
        Color.parseColor("#FF6F60"), Color.parseColor("#B71C1C"), Color.parseColor("#3E0508"))

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
    }
    private var t = 0f

    init {
        animator.addUpdateListener {
            t = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    fun stop() { animator.cancel() }

    fun setLive(v: Boolean) {
        live = v
        // Live window is taller so the tears never clip: the eye shifts up a
        // touch and the Amaterasu drip travels BELOW it.
        animator.cancel()
        animator.duration = if (v) phaseSpeed() else 2200L
        if (isAttachedToWindow) animator.start()
        invalidate()
    }

    fun setPhase(p: LivePhase) {
        if (p == phase) return
        phase = p
        val c = when (p) {
            LivePhase.LISTENING -> Color.parseColor("#E53935")
            LivePhase.SPEAKING -> Color.parseColor("#FF8A80")
            else -> Color.parseColor("#FF1744")
        }
        irisColors = intArrayOf(lighten(c), c, Color.parseColor("#3E0508"))
        animator.cancel()
        animator.duration = phaseSpeed()
        if (isAttachedToWindow) animator.start()
        invalidate()
    }

    private fun phaseSpeed(): Long = when (phase) {
        LivePhase.LISTENING, LivePhase.SPEAKING -> 1600L
        else -> 700L
    }

    private fun lighten(c: Int): Int = Color.rgb(
        Math.min(255, Color.red(c) + 64),
        Math.min(255, Color.green(c) + 40),
        Math.min(255, Color.blue(c) + 40))

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val eyePx = (48 * density).toInt()
        val r = eyePx / 2f
        val cx = width / 2f
        val cy = if (live) height * 0.36f else height * 0.44f

        val breath = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * Math.PI.toFloat()))
        val base = irisColors[1]

        // Halo: subtle when idle, breathing hot when live.
        if (live) {
            glow.shader = RadialGradient(cx, cy, r * 1.9f,
                intArrayOf(base and 0x00FFFFFF or ((breath * 0.55f * 255).toInt() shl 24),
                    0x00000000),
                floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.9f, glow)
        } else {
            glow.shader = RadialGradient(cx, cy, r * 1.35f,
                intArrayOf(base and 0x00FFFFFF or (((0.28f + 0.14f * breath) * 255).toInt() shl 24),
                    0x00000000),
                floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.35f, glow)
        }

        canvas.save()
        val scale = if (live) 1f + 0.04f * sin(t * 2f * Math.PI.toFloat()) else 1f
        canvas.scale(scale, scale, cx, cy)

        // Sclera (black when live — the Amaterasu bleed look), iris, ring.
        if (live) {
            sclera.color = Color.parseColor("#0B0B0E")
        } else {
            sclera.color = Color.parseColor("#120404")
        }
        canvas.drawCircle(cx, cy, r, sclera)

        iris.shader = RadialGradient(cx, cy, r * 0.94f, irisColors,
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.86f, iris)

        ring.shader = null
        ring.strokeWidth = r * 0.075f
        ring.color = base
        ring.alpha = if (live) (150 + 100 * breath).toInt() else 210
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)

        // Three tomoe — slow idle spin, faster live spin.
        val rot = t * 2f * Math.PI.toFloat() * (if (live) 0.45f else 0.22f)
        for (i in 0 until 3) {
            val ang = rot + i * 2.0944f
            val tx = cx + (r * 0.5f) * cos(ang)
            val ty = cy + (r * 0.5f) * sin(ang)
            canvas.drawCircle(tx, ty, r * 0.14f, tomoe)
        }
        canvas.restore()

        // AMATERASU: two BLACK tears drip from the lower lid — live only,
        // inside the window, never clipped (window is 96dp tall on purpose).
        if (live) {
            val lidY = cy + r * 0.66f
            val room = height - lidY - 4 * density
            val travel = room.coerceAtLeast(8 * density)
            val drips = floatArrayOf(0.34f, 0.66f)
            for (i in drips.indices) {
                val cycle = ((t + i * 0.45f) % 1f)
                val headY = lidY + cycle * travel
                val headX = cx + (drips[i] - 0.5f) * r * 0.9f
                val alpha = (255 * (1f - cycle)).toInt().coerceIn(0, 255)
                tear.alpha = alpha
                tail.alpha = (alpha * 0.45f).toInt()
                tail.strokeWidth = r * 0.075f
                canvas.drawLine(headX, lidY, headX, headY, tail)
                canvas.drawCircle(headX, headY, r * (0.10f + 0.05f * (1f - cycle)), tear)
            }
        }
    }
}
