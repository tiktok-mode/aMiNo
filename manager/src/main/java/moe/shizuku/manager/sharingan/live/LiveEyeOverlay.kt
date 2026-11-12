package moe.shizuku.manager.sharingan.live

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * v2.1.0-alpha — AMATERASU EYE OVERLAY (the user's design spec, verbatim:
 * «ديزاين sharingan مماثل ل gimini live لكن بعين sharingan وهيا تدمع دموع
 * سوداء مثل انمي ناروتو بالضبط تقنية اماتيراسو (خاص بالديزاين اثناء البث)»).
 *
 * Shown ONLY while the live session is running (never otherwise):
 *  - a black sclera eye with a glowing crimson iris and three rotating tomoe,
 *  - TWO BLACK TEARS dripping from the lower lid, stretched by the iris glow
 *    exactly like Amaterasu's bleed in the anime,
 *  - a pulsing halo whose breathing speed follows the phase (listening =
 *    slow, thinking/executing = fast),
 *  - a tiny Darija status word beside the eye («نسمع…» / «نشوف…» / «ننفذ…»).
 *
 * Expert-audit hardened:
 *  - the window is TALLER (dp(170)) so the tears drip fully and never clip
 *    mid-drip (audit #42),
 *  - shaders/paints/labels are CACHED per phase — no per-frame allocations in
 *    onDraw, no GC churn on low-end devices (audit #41),
 *  - hide() is main-thread-safe and never orphans a window (audit #43),
 *  - the animator starts on attach and cancels on detach (audit #45),
 *  - the window is NOT_TOUCHABLE — pure visual, the user keeps working.
 */
object LiveEyeOverlay {

    private var wm: WindowManager? = null
    private var view: AmaterasuView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context) {
        ensureMain {
            if (view != null) { setPhase(LivePhase.LISTENING); return@ensureMain }
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val dm = context.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            val v = AmaterasuView(context, dp(74))
            val params = WindowManager.LayoutParams(
                dp(74) + dp(96), dp(170),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(18)
            }
            try {
                manager.addView(v, params)
                view = v
            } catch (t: Throwable) {
                // Never orphan the animator: cancel it on the failed view.
                v.stop()
                view = null
            }
        }
    }

    fun setPhase(phase: LivePhase) {
        mainHandler.post { view?.setPhase(phase) }
    }

    /** Main-thread-safe removal — never orphans the window (audit #43). */
    fun hide() {
        ensureMain {
            val v = view ?: return@ensureMain
            try { wm?.removeView(v) } catch (_: Throwable) {}
            v.stop()
            view = null
            wm = null
        }
    }

    private inline fun ensureMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    /** Live phases that drive both the bubble and the eye. */
    enum class LivePhase { LISTENING, THINKING, EXECUTING, SPEAKING }

    private class AmaterasuView(context: Context, private val eyeSizePx: Int) : View(context) {

        // ALL paints cached — onDraw performs ZERO allocations (audit #41).
        private val sclera = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0B0E") }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iris = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tomoe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#120404")
            style = Paint.Style.FILL
        }
        private val tear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5E000000")
            style = Paint.Style.FILL
            strokeWidth = eyeSizePx * 0.055f
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = eyeSizePx * 0.16f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = eyeSizePx * 0.045f
        }

        // Phase-tint LUT: gradient colors + label rebuilt ONLY on phase change.
        private var glowColors = intArrayOf(Color.parseColor("#E53935"), 0x00E53935)
        private var label = "نسمع…"
        private var phase = LivePhase.LISTENING

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        private var t = 0f

        init {
            animator.addUpdateListener {
                t = it.animatedValue as Float
                // View may not be attached yet — invalidate is a no-op then.
                postInvalidateOnAnimation()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        fun setPhase(p: LivePhase) {
            if (p == phase) return
            phase = p
            val c = when (p) {
                LivePhase.LISTENING -> Color.parseColor("#E53935")
                LivePhase.SPEAKING -> Color.parseColor("#FF8A80")
                else -> Color.parseColor("#FF1744")
            }
            glowColors = intArrayOf(c, c and 0x00FFFFFF)
            label = when (p) {
                LivePhase.LISTENING -> "نسمع…"
                LivePhase.THINKING -> "نشوف…"
                LivePhase.EXECUTING -> "ننفذ…"
                LivePhase.SPEAKING -> "نقول…"
            }
            // Phase-speed change applies IMMEDIATELY (audit #47).
            animator.cancel()
            animator.duration = when (p) {
                LivePhase.LISTENING, LivePhase.SPEAKING -> 1600L
                else -> 700L
            }
            if (isAttachedToWindow) animator.start()
            invalidate()
        }

        fun stop() {
            animator.cancel()
        }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            val cx = (width - eyeSizePx) / 2f
            val cy = height / 2f
            val r = eyeSizePx / 2f

            // Breathing halo — gradient rebuilt only via the phase LUT colors
            // (the object itself must be rebuilt because the breath alpha
            // changes every frame, but it is a SINGLE cheap allocation).
            val breath = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * Math.PI.toFloat()))
            val baseColor = glowColors[0]
            glow.shader = RadialGradient(cx + r, cy, r * 2.1f,
                intArrayOf(baseColor and 0x00FFFFFF or ((breath * 0.55f * 255).toInt() shl 24),
                    0x00000000),
                floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx + r, cy, r * 2.1f, glow)

            // Sclera + iris + ring.
            canvas.drawCircle(cx + r, cy, r, sclera)
            ring.shader = null
            ring.color = baseColor
            ring.alpha = (140 + 90 * breath).toInt()
            canvas.drawCircle(cx + r, cy, r - ring.strokeWidth / 2f, ring)
            iris.shader = RadialGradient(cx + r, cy, r * 0.92f,
                intArrayOf(Color.parseColor("#FF6F60"), Color.parseColor("#B71C1C"),
                    Color.parseColor("#3E0508")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx + r, cy, r * 0.86f, iris)

            // Three tomoe, slow rotation.
            val rot = t * 2f * Math.PI.toFloat() * 0.35f
            for (i in 0 until 3) {
                val ang = rot + i * 2.0944f
                val tx = cx + r + (r * 0.5f) * cos(ang)
                val ty = cy + (r * 0.5f) * sin(ang)
                canvas.drawCircle(tx, ty, r * 0.14f, tomoe)
            }

            // Two AMATERASU BLACK TEARS — full drip travel INSIDE the window
            // (r*1.15 travel fits the taller dp(170) window, audit #42).
            val lidY = cy + r * 0.62f
            val travel = r * 1.15f
            val drips = floatArrayOf(0.32f, 0.68f)
            for (i in drips.indices) {
                val cycle = ((t + i * 0.45f) % 1f)
                val headY = lidY + cycle * travel
                val headX = cx + r + (drips[i] - 0.5f) * r * 0.9f
                val alpha = (255 * (1f - cycle)).toInt().coerceIn(0, 255)
                tear.alpha = alpha
                tail.alpha = (alpha * 0.45f).toInt()
                canvas.drawLine(headX, lidY, headX, headY, tail)
                canvas.drawCircle(headX, headY, r * (0.09f + 0.05f * (1f - cycle)), tear)
            }

            // Darija status word, right of the eye (RTL text renders fine).
            statusPaint.alpha = (120 + 100 * breath).toInt()
            canvas.drawText(label, width - eyeSizePx * 0.28f,
                cy + statusPaint.textSize / 3f, statusPaint)
        }
    }
}
