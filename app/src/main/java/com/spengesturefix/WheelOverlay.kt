package com.spengesturefix

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
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
 * The window and its view are transparent. Only the action targets and two
 * subtle guide arcs are drawn, so the application underneath remains visible
 * and the overlay adds very little work to the main thread or GPU.
 */
class WheelOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wheelView: WheelView? = null
    private var visible = false

    fun toggle() {
        mainHandler.post {
            if (visible) dismissOnMain() else showOnMain()
        }
    }

    fun show() {
        mainHandler.post { showOnMain() }
    }

    fun dismiss() {
        mainHandler.post { dismissOnMain() }
    }

    private fun showOnMain() {
        if (TabletModeState.isActive) return

        val existing = wheelView
        if (existing != null) {
            visible = true
            existing.playEnterAnimation()
            return
        }

        val metrics = appContext.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val minimum = dp(250f).coerceAtMost(shortSide)
        val size = (shortSide * 0.68f).roundToInt()
            .coerceAtLeast(minimum)
            .coerceAtMost(shortSide)
        val inset = dp(8f)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
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
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val center = PointF()
    private val guideRect = RectF()
    private val innerGuideRect = RectF()
    private val slotCenters = ArrayList<PointF>()
    private val labels = slots.map { compactLabel(it.label) }
    private var ringRadius = 0f
    private var buttonRadius = 0f
    private var closeRadius = 0f
    private val startAngle = Math.PI
    // Canvas angles increase clockwise because the Y axis points down. From
    // the left anchor, a 90-degree sweep travels cleanly upward without
    // clipping the upper target at the right edge.
    private val sweepAngle = Math.PI / 2.0
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
        center.set(side * 0.84f, side * 0.84f)

        buttonRadius = (side * 0.073f)
            .coerceAtLeast(dp(20f).toFloat())
            .coerceAtMost(side * 0.088f)
        closeRadius = buttonRadius * 0.90f
        ringRadius = (side * 0.60f).coerceAtLeast(buttonRadius * 2.7f)
        ringRadius = min(
            ringRadius,
            min(
                center.x - buttonRadius - dp(12f),
                center.y - buttonRadius - dp(12f)
            ).coerceAtLeast(buttonRadius * 2f)
        )

        val guideRadius = ringRadius + buttonRadius * 1.30f
        guideRect.set(
            center.x - guideRadius,
            center.y - guideRadius,
            center.x + guideRadius,
            center.y + guideRadius
        )
        val innerRadius = guideRadius - dp(8f)
        innerGuideRect.set(
            center.x - innerRadius,
            center.y - innerRadius,
            center.x + innerRadius,
            center.y + innerRadius
        )

        slotCenters.clear()
        slots.forEachIndexed { index, _ ->
            val denominator = (slots.size - 1).coerceAtLeast(1).toDouble()
            val fraction = if (slots.size == 1) 0.5 else index.toDouble() / denominator
            val angle = startAngle + sweepAngle * fraction
            slotCenters += PointF(
                center.x + ringRadius * cos(angle).toFloat(),
                center.y + ringRadius * sin(angle).toFloat()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return

        val scale = 0.92f + 0.08f * progress
        val alpha = (progress * 255f).roundToInt().coerceIn(0, 255)
        canvas.save()
        canvas.scale(scale, scale, center.x, center.y)
        drawGuide(canvas, alpha)
        drawConnectors(canvas, alpha)
        drawButtons(canvas, alpha)
        drawCloseButton(canvas, alpha)
        canvas.restore()
    }

    private fun drawGuide(canvas: Canvas, alpha: Int) {
        accentPaint.strokeWidth = dp(1.5f).toFloat()
        accentPaint.color = accent.withAlpha((alpha * 0.42f).roundToInt())
        canvas.drawArc(
            guideRect,
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false,
            accentPaint
        )

        accentPaint.strokeWidth = dp(1f).toFloat()
        accentPaint.color = Color.argb((alpha * 0.16f).roundToInt(), 255, 255, 255)
        canvas.drawArc(
            innerGuideRect,
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false,
            accentPaint
        )
    }

    private fun drawConnectors(canvas: Canvas, alpha: Int) {
        connectorPaint.strokeWidth = dp(1f).toFloat()
        connectorPaint.color = accent.withAlpha((alpha * 0.24f).roundToInt())
        slotCenters.forEachIndexed { index, point ->
            val denominator = (slots.size - 1).coerceAtLeast(1).toDouble()
            val fraction = if (slots.size == 1) 0.5 else index.toDouble() / denominator
            val angle = startAngle + sweepAngle * fraction
            val startRadius = ringRadius - buttonRadius - dp(5f)
            val endRadius = ringRadius - buttonRadius + dp(2f)
            canvas.drawLine(
                center.x + startRadius * cos(angle).toFloat(),
                center.y + startRadius * sin(angle).toFloat(),
                center.x + endRadius * cos(angle).toFloat(),
                center.y + endRadius * sin(angle).toFloat(),
                connectorPaint
            )
            canvas.drawCircle(point.x, point.y, dp(2f).toFloat(), connectorPaint)
        }
    }

    private fun drawButtons(canvas: Canvas, alpha: Int) {
        val iconSize = dp(16f).toFloat()
        val labelSize = dp(7f).toFloat()
        slotCenters.forEachIndexed { index, point ->
            val selected = selectedSlot == index
            val radius = buttonRadius * if (selected) 1.10f else 1f
            if (!selected) {
                buttonPaint.color = accent.withAlpha((alpha * 0.10f).roundToInt())
                canvas.drawCircle(point.x, point.y, radius + dp(4f), buttonPaint)
            }
            buttonPaint.color = if (selected) {
                accent.withAlpha((alpha * 0.92f).roundToInt())
            } else {
                Color.argb((alpha * 0.76f).roundToInt(), 17, 23, 35)
            }
            canvas.drawCircle(point.x, point.y, radius, buttonPaint)

            buttonBorderPaint.strokeWidth = if (selected) dp(2f).toFloat() else dp(1f).toFloat()
            buttonBorderPaint.color = if (selected) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                accent.withAlpha((alpha * 0.72f).roundToInt())
            }
            canvas.drawCircle(point.x, point.y, radius, buttonBorderPaint)

            iconPaint.textSize = iconSize
            iconPaint.color = if (selected) Color.rgb(8, 12, 20) else Color.WHITE
            canvas.drawText(slots[index].type.icon, point.x, point.y - dp(1f), iconPaint)

            labelPaint.textSize = labelSize
            labelPaint.color = if (selected) {
                Color.argb(alpha, 8, 12, 20)
            } else {
                Color.argb(alpha, 232, 235, 242)
            }
            canvas.drawText(
                labels[index],
                point.x,
                point.y + buttonRadius * 0.62f,
                labelPaint
            )
        }
    }

    private fun drawCloseButton(canvas: Canvas, alpha: Int) {
        buttonPaint.color = Color.argb((alpha * 0.82f).roundToInt(), 14, 19, 30)
        canvas.drawCircle(center.x, center.y, closeRadius, buttonPaint)
        buttonBorderPaint.strokeWidth = dp(1.5f).toFloat()
        buttonBorderPaint.color = accent.withAlpha((alpha * 0.95f).roundToInt())
        canvas.drawCircle(center.x, center.y, closeRadius, buttonBorderPaint)

        val cross = closeRadius * 0.42f
        connectorPaint.strokeWidth = dp(2f).toFloat()
        connectorPaint.color = Color.argb(alpha, 245, 246, 250)
        canvas.drawLine(center.x - cross, center.y - cross, center.x + cross, center.y + cross, connectorPaint)
        canvas.drawLine(center.x + cross, center.y - cross, center.x - cross, center.y + cross, connectorPaint)
    }

    private fun contentPoint(x: Float, y: Float): Pair<Float, Float> {
        val scale = (0.92f + 0.08f * progress).coerceAtLeast(0.01f)
        return center.x + (x - center.x) / scale to
            center.y + (y - center.y) / scale
    }

    private fun hitSlot(x: Float, y: Float): Int {
        val (pointX, pointY) = contentPoint(x, y)
        slotCenters.forEachIndexed { index, slot ->
            if (hypot(pointX - slot.x, pointY - slot.y) <= buttonRadius * 1.20f) {
                return index
            }
        }
        return -1
    }

    private fun isInCloseButton(x: Float, y: Float): Boolean {
        val (pointX, pointY) = contentPoint(x, y)
        return hypot(pointX - center.x, pointY - center.y) <= closeRadius * 1.30f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                tracking = false
                selectedSlot = -1
                onDismiss()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                val slot = hitSlot(event.x, event.y)
                val close = isInCloseButton(event.x, event.y)
                if (slot < 0 && !close) {
                    onDismiss()
                    return true
                }
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
        animateTo(1f, 90L, null)
    }

    fun playExitAnimation(onFinished: (WheelView) -> Unit) {
        interactive = false
        tracking = false
        selectedSlot = -1
        animateTo(0f, 80L, object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
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
        return if (normalized.length > 10) normalized.take(9) + '…' else normalized
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )
}
