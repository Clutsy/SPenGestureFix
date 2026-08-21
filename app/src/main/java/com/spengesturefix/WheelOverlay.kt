package com.spengesturefix

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compact Air Command-style fan anchored to the lower-right screen corner.
 *
 * It is a small custom View rather than a full-screen Compose overlay. Only
 * the wheel's bounds are a touch window; the rest of the Android input path
 * remains untouched. Drawing is Canvas-only, with no bitmap decode or blur.
 */
class WheelOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wheelView: WheelView? = null
    private var visible = false

    fun toggle() {
        mainHandler.post { if (visible) dismissOnMain() else showOnMain() }
    }

    fun show() {
        mainHandler.post { showOnMain() }
    }

    fun dismiss() {
        mainHandler.post { dismissOnMain() }
    }

    private fun showOnMain() {
        val existing = wheelView
        if (existing != null) {
            visible = true
            existing.playEnterAnimation()
            return
        }

        val metrics = appContext.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val minimum = dp(220f).coerceAtMost(shortSide)
        val size = (shortSide * 0.64f).roundToInt()
            .coerceAtLeast(minimum)
            .coerceAtMost(shortSide)
        val inset = dp(6f)
        val view = WheelView(
            context = appContext,
            slots = WheelConfig.loadSlots(appContext),
            accent = WheelConfig.getWheelColor(appContext).toArgb(),
            onSlotTapped = { action ->
                if (!TabletModeState.isActive) {
                    ActionExecutor.execute(appContext, action, this)
                }
                dismiss()
            },
            onDismiss = ::dismiss
        )
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            // Gravity.END is intentional: all fan actions open into the
            // display instead of being clipped by the right edge.
            gravity = Gravity.BOTTOM or Gravity.END
            x = inset
            y = inset
        }

        try {
            windowManager.addView(view, params)
            wheelView = view
            visible = true
            view.playEnterAnimation()
        } catch (_: WindowManager.BadTokenException) {
            visible = false
        } catch (_: SecurityException) {
            visible = false
        } catch (_: RuntimeException) {
            visible = false
        }
    }

    private fun dismissOnMain() {
        if (!visible) return
        visible = false
        wheelView?.playExitAnimation { view ->
            if (wheelView !== view) return@playExitAnimation
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // The host may have removed the window during service shutdown.
            } finally {
                wheelView = null
            }
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            appContext.resources.displayMetrics
        ).roundToInt()
}

private class WheelView(
    context: Context,
    private val slots: List<PenAction>,
    private val accent: Int,
    private val onSlotTapped: (PenAction) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.NORMAL
        )
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD
        )
    }
    private val panelPath = Path()
    private val center = PointF()
    private var ringRadius = 0f
    private var buttonRadius = 0f
    private var closeRadius = 0f
    // At the lower-right corner, the usable quadrant runs left → up.
    private val startAngle = Math.PI
    private val sweepAngle = -Math.PI / 2.0
    private var selectedSlot = -1
    private var tracking = false
    private var animation: ValueAnimator? = null
    private var progress = 0f
    private var interactive = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.section_wheel)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val side = min(width, height).toFloat()
        center.set(side * 0.845f, side * 0.845f)
        // Smaller buttons plus a longer radius give each action a distinct,
        // comfortable hit target instead of a crowded stack.
        buttonRadius = (side * 0.075f)
            .coerceAtLeast(dp(18f).toFloat())
            .coerceAtMost(side * 0.085f)
        closeRadius = buttonRadius * 0.82f
        ringRadius = (side * 0.58f).coerceAtLeast(buttonRadius * 2.25f)
        ringRadius = min(
            ringRadius,
            min(
                center.x - buttonRadius - dp(4f),
                center.y - buttonRadius - dp(4f)
            ).coerceAtLeast(buttonRadius * 2f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return

        val scale = 0.88f + 0.12f * progress
        val alpha = (progress * 255f).roundToInt().coerceIn(0, 255)
        canvas.save()
        canvas.scale(scale, scale, center.x, center.y)
        drawPanel(canvas, alpha)
        drawConnectors(canvas, alpha)
        drawButtons(canvas, alpha)
        drawCloseButton(canvas, alpha)
        canvas.restore()
    }

    private fun drawPanel(canvas: Canvas, alpha: Int) {
        val outer = ringRadius + buttonRadius + dp(15f)
        panelPath.reset()
        panelPath.moveTo(center.x, center.y)
        panelPath.lineTo(
            center.x + cos(startAngle).toFloat() * outer,
            center.y + sin(startAngle).toFloat() * outer
        )
        panelPath.arcTo(
            RectF(center.x - outer, center.y - outer, center.x + outer, center.y + outer),
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false
        )
        panelPath.close()

        // The dark fan gives the actions a clear silhouette without covering
        // or blurring the application below the compact window.
        outerPaint.shader = RadialGradient(
            center.x,
            center.y,
            outer,
            intArrayOf(
                Color.argb((alpha * 0.97f).roundToInt(), 15, 18, 28),
                Color.argb((alpha * 0.91f).roundToInt(), 9, 11, 18),
                Color.argb((alpha * 0.58f).roundToInt(), 7, 8, 13)
            ),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(panelPath, outerPaint)
        outerPaint.shader = null

        arcPaint.strokeWidth = dp(2f).toFloat()
        arcPaint.color = accent.withAlpha((alpha * 0.84f).roundToInt())
        canvas.drawArc(
            RectF(center.x - outer, center.y - outer, center.x + outer, center.y + outer),
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false,
            arcPaint
        )
        arcPaint.strokeWidth = dp(1f).toFloat()
        arcPaint.color = Color.argb((alpha * 0.22f).roundToInt(), 255, 255, 255)
        canvas.drawArc(
            RectF(center.x - outer + dp(8f), center.y - outer + dp(8f),
                center.x + outer - dp(8f), center.y + outer - dp(8f)),
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false,
            arcPaint
        )
    }

    private fun drawConnectors(canvas: Canvas, alpha: Int) {
        connectorPaint.strokeWidth = dp(1f).toFloat()
        connectorPaint.color = accent.withAlpha((alpha * 0.30f).roundToInt())
        slots.forEachIndexed { index, _ ->
            val point = slotCenter(index)
            canvas.drawLine(center.x, center.y, point.x, point.y, connectorPaint)
            canvas.drawCircle(point.x, point.y, dp(2f).toFloat(), connectorPaint)
        }
    }

    private fun drawButtons(canvas: Canvas, alpha: Int) {
        val iconSize = dp(17f).toFloat()
        val labelSize = dp(8.5f).toFloat()
        slots.forEachIndexed { index, action ->
            val point = slotCenter(index)
            val selected = selectedSlot == index
            val radius = buttonRadius * if (selected) 1.08f else 1f
            val buttonAlpha = if (selected) alpha else (alpha * 0.96f).roundToInt()

            buttonPaint.shader = if (selected) {
                LinearGradient(
                    point.x - radius,
                    point.y - radius,
                    point.x + radius,
                    point.y + radius,
                    lighten(accent, 0.22f, buttonAlpha),
                    accent.withAlpha(buttonAlpha),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    point.x,
                    point.y - radius,
                    point.x,
                    point.y + radius,
                    Color.argb(buttonAlpha, 35, 40, 55),
                    Color.argb(buttonAlpha, 16, 19, 29),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(point.x, point.y, radius, buttonPaint)
            buttonPaint.shader = null

            buttonBorderPaint.strokeWidth = if (selected) dp(2f).toFloat() else dp(1f).toFloat()
            buttonBorderPaint.color = if (selected) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                accent.withAlpha((alpha * 0.70f).roundToInt())
            }
            canvas.drawCircle(point.x, point.y, radius, buttonBorderPaint)

            iconPaint.textSize = iconSize
            iconPaint.color = if (selected) Color.rgb(9, 12, 19) else Color.WHITE
            canvas.drawText(action.type.icon, point.x, point.y - dp(1f), iconPaint)

            labelPaint.textSize = labelSize
            labelPaint.color = if (selected) Color.argb(alpha, 9, 12, 19)
            else Color.argb(alpha, 226, 228, 235)
            canvas.drawText(
                compactLabel(action.label),
                point.x,
                point.y + buttonRadius * 0.63f,
                labelPaint
            )
        }
    }

    private fun drawCloseButton(canvas: Canvas, alpha: Int) {
        buttonPaint.shader = RadialGradient(
            center.x,
            center.y,
            closeRadius,
            Color.argb(alpha, 47, 51, 68),
            Color.argb(alpha, 12, 15, 23),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(center.x, center.y, closeRadius, buttonPaint)
        buttonPaint.shader = null
        buttonBorderPaint.strokeWidth = dp(1.5f).toFloat()
        buttonBorderPaint.color = accent.withAlpha((alpha * 0.92f).roundToInt())
        canvas.drawCircle(center.x, center.y, closeRadius, buttonBorderPaint)

        val cross = dp(8f).toFloat()
        connectorPaint.strokeWidth = dp(2f).toFloat()
        connectorPaint.color = Color.argb(alpha, 245, 246, 250)
        canvas.drawLine(center.x - cross, center.y - cross, center.x + cross, center.y + cross, connectorPaint)
        canvas.drawLine(center.x + cross, center.y - cross, center.x - cross, center.y + cross, connectorPaint)
    }

    private fun slotCenter(index: Int): PointF {
        val count = slots.size.coerceAtLeast(1)
        val fraction = if (count == 1) 0.5 else index.toDouble() / (count - 1).toDouble()
        val angle = startAngle + sweepAngle * fraction
        return PointF(
            center.x + ringRadius * cos(angle).toFloat(),
            center.y + ringRadius * sin(angle).toFloat()
        )
    }

    private fun hitSlot(x: Float, y: Float): Int {
        slots.forEachIndexed { index, _ ->
            val point = slotCenter(index)
            if (hypot(x - point.x, y - point.y) <= buttonRadius * 1.14f) return index
        }
        return -1
    }

    private fun isInCloseButton(x: Float, y: Float): Boolean =
        hypot(x - center.x, y - center.y) <= closeRadius * 1.25f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slot = hitSlot(event.x, event.y)
                if (slot < 0 && !isInCloseButton(event.x, event.y)) return false
                tracking = true
                selectedSlot = slot
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                selectedSlot = hitSlot(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val releasedSlot = hitSlot(event.x, event.y)
                val close = isInCloseButton(event.x, event.y)
                tracking = false
                selectedSlot = -1
                invalidate()
                performClick()
                when {
                    close -> onDismiss()
                    releasedSlot >= 0 -> onSlotTapped(slots[releasedSlot])
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                selectedSlot = -1
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = WheelView::class.java.name
        info.isClickable = true
    }

    fun playEnterAnimation() {
        interactive = true
        animateTo(1f, 150L, null)
    }

    fun playExitAnimation(onFinished: (WheelView) -> Unit) {
        interactive = false
        tracking = false
        selectedSlot = -1
        animateTo(0f, 120L, object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // A quick re-open cancels the exit animator. Never remove a
                // window that has become visible again.
                if (!interactive && progress <= 0.01f) onFinished(this@WheelView)
            }
        })
    }

    private fun animateTo(target: Float, duration: Long, listener: Animator.AnimatorListener?) {
        animation?.cancel()
        val animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            listener?.let(::addListener)
        }
        animation = animator
        animator.start()
    }

    private fun compactLabel(value: String): String {
        val normalized = value.replace('\n', ' ').trim()
        return if (normalized.length > 12) normalized.take(11) + '…' else normalized
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )

    private fun lighten(color: Int, amount: Float, alpha: Int): Int {
        val red = (Color.red(color) + (255 - Color.red(color)) * amount).roundToInt()
        val green = (Color.green(color) + (255 - Color.green(color)) * amount).roundToInt()
        val blue = (Color.blue(color) + (255 - Color.blue(color)) * amount).roundToInt()
        return Color.argb(alpha, red, green, blue)
    }
}
