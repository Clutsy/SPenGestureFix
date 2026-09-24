package com.spengesturefix

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import androidx.compose.ui.graphics.toArgb
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Air Command-style wheel anchored to the lower-right screen corner.
 *
 * Design goals (2026.09 refinement):
 *  - classic lower-right quarter-arc fan with ONE even ring: equal angular
 *    steps, no staggered rings, no overlapping targets;
 *  - real vector icons drawn from tinted VectorDrawables instead of emoji;
 *  - short localized labels under the icon, only inside the slot disc;
 *  - closing is the dedicated center disc (X icon), always the same place;
 *  - press-and-drag selection like the Samsung original with a haptic tick
 *    when the finger crosses into a new slot;
 *  - soft radial backdrop so the wheel stays readable over any app.
 */
class WheelOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wheelView: View? = null
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

        // Custom open sound (SpenCommand's "Sound setting"), if configured.
        // Plays for BOTH styles: the sounds belong to the wheel, not to one skin.
        WheelSoundStore.play(appContext, open = true)

        val existing = wheelView
        if (existing != null) {
            visible = true
            when (existing) {
                is WheelView -> existing.playEnterAnimation()
                is RetroWheelView -> existing.playEnterAnimation()
            }
            return
        }

        val metrics = appContext.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val retro = WheelStyle.load(appContext) == WheelStyle.RETRO
        // Classic serves the configured count on discs spread evenly along
        // the authentic button spiral (4–7; the artwork disc spots are only
        // the reference path, the original app laid its buttons at runtime).
        val slots = WheelConfig.loadSlots(appContext)
        // RETRO sizes the original SpenCommand window by the number of
        // placed gestures: the fan scales 150/165/180dp (4/5/6 discs) so
        // fewer slots never leave dead space around the artwork.
        val size = if (retro) {
            dp(RetroWheelLayout.panelDpFor(slots.size))
        } else {
            val minimum = dp(240f).coerceAtMost(shortSide)
            (shortSide * 0.66f).roundToInt()
                .coerceAtLeast(minimum)
                .coerceAtMost(shortSide)
        }
        val inset = dp(10f)
        val accent = WheelConfig.getWheelColor(appContext).toArgb()
        val onSlotTapped: (PenAction) -> Unit = { action ->
            if (!TabletModeState.isActive) {
                ActionExecutor.execute(appContext, action, this)
            }
            dismiss()
        }
        val view = if (retro) {
            RetroWheelView(
                context = appContext,
                slots = slots,
                accent = accent,
                onSlotTapped = onSlotTapped,
                onDismiss = ::dismiss
            )
        } else {
            WheelView(
                context = appContext,
                slots = slots,
                accent = accent,
                onSlotTapped = onSlotTapped,
                onDismiss = ::dismiss
            )
        }
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
            // Anchored at the lower-right like the original Note Air Command.
            gravity = Gravity.BOTTOM or Gravity.END
            x = inset
            y = inset
        }

        try {
            windowManager.addView(view, params)
            wheelView = view
            visible = true
            when (view) {
                is WheelView -> view.playEnterAnimation()
                is RetroWheelView -> view.playEnterAnimation()
            }
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
        WheelSoundStore.play(appContext, open = false)
        val current = wheelView
        when (current) {
            is WheelView -> current.playExitAnimation { view -> removeIfCurrent(view) }
            is RetroWheelView -> current.playExitAnimation { view -> removeIfCurrent(view) }
        }
    }

    private fun removeIfCurrent(view: View) {
        if (wheelView !== view) return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The host may have removed the window during service shutdown.
        } finally {
            wheelView = null
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            appContext.resources.displayMetrics
        ).roundToInt()
}

/**
 * Pure geometry for the lower-right quarter-arc fan. Exposed for unit tests:
 * the slot angle math is intentionally deterministic and hardware-free.
 */
object WheelGeometry {
    /**
     * Slot k angle in radians: slot 0 anchors at the left (180°) and the fan
     * sweeps upward to the top (90°) in exactly even steps, like the original
     * Samsung Air Command anchored at the lower-right corner.
     */
    fun slotAngle(index: Int, count: Int): Double {
        val denominator = (count - 1).coerceAtLeast(1).toDouble()
        val fraction = if (count == 1) 0.5 else index.toDouble() / denominator
        return PI + (PI / 2.0) * fraction
    }

    /**
     * Chord distance between adjacent slot centers on the quarter arc of
     * [ringRadius]: 2R·sin(step/2) with step = 90°/(count-1). Adjacent discs
     * never touch as long as this stays above twice the disc radius.
     */
    fun minimumCenterDistance(count: Int, ringRadius: Float): Float {
        if (count < 2) return Float.MAX_VALUE
        val step = (PI / 2.0) / (count - 1).coerceAtLeast(1)
        return (2.0 * ringRadius * sin(step / 2.0)).toFloat()
    }

    /**
     * Slot center as a plain (x, y) pair. Returning a Pair instead of an
     * Android class keeps this function testable on the JVM.
     */
    fun slotCenter(
        index: Int,
        count: Int,
        centerX: Float,
        centerY: Float,
        ringRadius: Float
    ): Pair<Float, Float> {
        val angle = slotAngle(index, count)
        return (centerX + ringRadius * cos(angle).toFloat()) to
            (centerY + ringRadius * sin(angle).toFloat())
    }
}

private class WheelView(
    context: Context,
    private val slots: List<PenAction>,
    private val accent: Int,
    private val onSlotTapped: (PenAction) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {
    private val appContext = context.applicationContext
    private val typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = typeface
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val center = PointF()
    private val guideRect = RectF()
    private val slotCenters = ArrayList<PointF>(slots.size + 1)
    private val labels = slots.map { compactLabel(it.label) }
    private val icons: List<android.graphics.drawable.Drawable?> = slots.map {
        loadIcon(it.type.iconResId)
    }
    private val closeIcon: android.graphics.drawable.Drawable? = loadIcon(R.drawable.ic_act_none)
    private var ringRadius = 0f
    private var slotRadius = 0f
    private var centerRadius = 0f
    private var iconSize = 0f
    private var iconBounds = android.graphics.Rect()

    // Canvas angles increase clockwise because Y points down. From the left
    // anchor a 90-degree sweep travels cleanly upward without clipping.
    private val startAngle = PI
    private val sweepAngle = PI / 2.0

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

    private fun loadIcon(resId: Int): android.graphics.drawable.Drawable? = try {
        // Native VectorDrawable on API 21+, compat inflater as the fallback.
        val drawable = appContext.getDrawable(resId)
            ?: VectorDrawableCompat.create(appContext.resources, resId, appContext.theme)
        drawable?.apply { setTint(Color.WHITE) }
    } catch (_: Exception) {
        null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val side = min(width, height).toFloat()
        // Anchored at the lower-right, like the original Note Air Command.
        center.set(side * 0.86f, side * 0.86f)

        // Every configured action gets a slot on one even quarter arc.
        val slotCount = slots.size.coerceAtLeast(1)
        centerRadius = (side * 0.072f).coerceAtLeast(dp(21f).toFloat())
        slotRadius = (side * 0.075f).coerceAtLeast(dp(22f).toFloat())

        // Ring radius sized so adjacent discs always keep an air gap:
        // chord(R) = 2R·sin(step/2) must exceed 2·slotRadius·margin. Capped
        // by what the corner-anchored window can actually show; the fan only
        // spans up-left from the anchor, so the arc always fits.
        val maxReachable = min(center.x, center.y) - slotRadius - dp(8f)
        val unitChord = WheelGeometry.minimumCenterDistance(slotCount, 1f)
        val needed = if (unitChord.isInfinite() || unitChord <= 0f) {
            0.45f
        } else {
            (slotRadius * 2.25f) / unitChord
        }
        ringRadius = (needed * side)
            .coerceAtLeast(side * 0.30f)
            .coerceAtMost(maxReachable)
            .coerceAtLeast(centerRadius + slotRadius + dp(10f))

        val guideRadius = ringRadius
        guideRect.set(
            center.x - guideRadius,
            center.y - guideRadius,
            center.x + guideRadius,
            center.y + guideRadius
        )

        slotCenters.clear()
        repeat(slotCount) { index ->
            val point = WheelGeometry.slotCenter(
                index = index,
                count = slotCount,
                centerX = center.x,
                centerY = center.y,
                ringRadius = ringRadius
            )
            slotCenters += PointF(point.first, point.second)
        }
        iconSize = slotRadius * 0.92f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return

        val scale = 0.92f + 0.08f * progress
        val alpha = (progress * 255f).roundToInt().coerceIn(0, 255)
        canvas.save()
        canvas.scale(scale, scale, center.x, center.y)
        drawBackdrop(canvas, alpha)
        drawGuideArc(canvas, alpha)
        drawSlots(canvas, alpha)
        drawCenter(canvas, alpha)
        canvas.restore()
    }

    private fun drawBackdrop(canvas: Canvas, alpha: Int) {
        val backdropRadius = ringRadius + slotRadius + centerRadius * 2.0f
        backdropPaint.shader = RadialGradient(
            center.x, center.y,
            backdropRadius,
            intArrayOf(
                Color.argb((alpha * 0.34f).roundToInt(), 8, 8, 14),
                Color.argb((alpha * 0.18f).roundToInt(), 8, 8, 14),
                Color.argb(0, 8, 8, 14)
            ),
            floatArrayOf(0f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(center.x, center.y, backdropRadius, backdropPaint)
    }

    private fun drawGuideArc(canvas: Canvas, alpha: Int) {
        // Subtle accent arc through every slot center (quarter fan only).
        arcPaint.strokeWidth = dp(1.5f).toFloat()
        arcPaint.color = accent.withAlpha((alpha * 0.35f).roundToInt())
        canvas.drawArc(
            guideRect,
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(sweepAngle).toFloat(),
            false,
            arcPaint
        )
    }

    private fun drawSlots(canvas: Canvas, alpha: Int) {
        val labelSize = slotRadius * 0.42f
        slotCenters.forEachIndexed { index, point ->
            val selected = selectedSlot == index
            val radius = if (selected) slotRadius * 1.12f else slotRadius

            if (selected) {
                glowPaint.color = accent.withAlpha((alpha * 0.30f).roundToInt())
                canvas.drawCircle(point.x, point.y, radius + dp(6f), glowPaint)
            }

            discPaint.color = if (selected) {
                accent.withAlpha((alpha * 0.96f).roundToInt())
            } else {
                Color.argb((alpha * 0.80f).roundToInt(), 17, 19, 30)
            }
            canvas.drawCircle(point.x, point.y, radius, discPaint)

            borderPaint.strokeWidth = if (selected) dp(2f).toFloat() else dp(1.2f).toFloat()
            borderPaint.color = if (selected) {
                Color.argb(alpha, 255, 255, 255)
            } else {
                accent.withAlpha((alpha * 0.55f).roundToInt())
            }
            canvas.drawCircle(point.x, point.y, radius, borderPaint)

            val icon = icons.getOrNull(index)
            if (icon != null) {
                val half = iconSize / 2f
                iconBounds.set(
                    (point.x - half).roundToInt(),
                    (point.y - half - labelSize * 0.18f).roundToInt(),
                    (point.x + half).roundToInt(),
                    (point.y + half - labelSize * 0.18f).roundToInt()
                )
                icon.bounds = iconBounds
                icon.setTint(if (selected) Color.argb(alpha, 10, 12, 20) else Color.argb(alpha, 255, 255, 255))
                icon.draw(canvas)
            }

            labelPaint.textSize = labelSize
            labelPaint.color = if (selected) {
                Color.argb(alpha, 10, 12, 20)
            } else {
                Color.argb((alpha * 0.92f).roundToInt(), 232, 234, 242)
            }
            canvas.drawText(labels[index], point.x, point.y + radius * 0.58f, labelPaint)
        }
    }

    private fun drawCenter(canvas: Canvas, alpha: Int) {
        val selected = selectedSlot == CLOSE_SLOT
        val radius = if (selected) centerRadius * 1.12f else centerRadius

        // Soft accent core behind the anchor point.
        glowPaint.shader = RadialGradient(
            center.x, center.y,
            radius * 2.4f,
            intArrayOf(
                accent.withAlpha((alpha * 0.30f).roundToInt()),
                accent.withAlpha(0)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(center.x, center.y, radius * 2.4f, glowPaint)
        glowPaint.shader = null

        if (selected) {
            glowPaint.color = accent.withAlpha((alpha * 0.30f).roundToInt())
            canvas.drawCircle(center.x, center.y, radius + dp(6f), glowPaint)
        }

        discPaint.color = if (selected) {
            Color.argb(alpha, 255, 255, 255)
        } else {
            accent.withAlpha((alpha * 0.90f).roundToInt())
        }
        canvas.drawCircle(center.x, center.y, radius, discPaint)

        borderPaint.strokeWidth = dp(1.5f).toFloat()
        borderPaint.color = if (selected) accent.withAlpha(alpha) else Color.argb((alpha * 0.85f).roundToInt(), 255, 255, 255)
        canvas.drawCircle(center.x, center.y, radius, borderPaint)

        val icon = closeIcon
        if (icon != null) {
            val half = radius * 0.44f
            iconBounds.set(
                (center.x - half).roundToInt(),
                (center.y - half).roundToInt(),
                (center.x + half).roundToInt(),
                (center.y + half).roundToInt()
            )
            icon.bounds = iconBounds
            icon.setTint(if (selected) accent.withAlpha(alpha) else Color.argb(alpha, 12, 14, 22))
            icon.draw(canvas)
        }
    }

    private fun contentPoint(x: Float, y: Float): Pair<Float, Float> {
        val scale = (0.92f + 0.08f * progress).coerceAtLeast(0.01f)
        return center.x + (x - center.x) / scale to
            center.y + (y - center.y) / scale
    }

    private fun hitSlot(x: Float, y: Float): Int {
        val (pointX, pointY) = contentPoint(x, y)
        val distanceFromCenter = hypot(pointX - center.x, pointY - center.y)
        if (distanceFromCenter <= centerRadius * 1.22f) return CLOSE_SLOT
        var best = -1
        var bestDistance = Float.MAX_VALUE
        slotCenters.forEachIndexed { index, slot ->
            val distance = hypot(pointX - slot.x, pointY - slot.y)
            if (distance <= slotRadius * 1.25f && distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
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
                if (slot < 0) {
                    onDismiss()
                    return true
                }
                tracking = true
                selectedSlot = slot
                performHaptic()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val slot = hitSlot(event.x, event.y)
                if (slot != selectedSlot) {
                    selectedSlot = slot
                    if (slot >= 0) performHaptic()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val releasedSlot = hitSlot(event.x, event.y)
                tracking = false
                selectedSlot = -1
                invalidate()
                performClick()
                when {
                    releasedSlot == CLOSE_SLOT -> onDismiss()
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

    private fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else {
                @Suppress("DEPRECATION")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (_: Exception) { }
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
        animateTo(1f, 150L, object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (interactive && progress >= 0.99f) performHaptic()
            }
        })
    }

    fun playExitAnimation(onFinished: (WheelView) -> Unit) {
        interactive = false
        tracking = false
        selectedSlot = -1
        animateTo(0f, 110L, object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!interactive && progress <= 0.01f) onFinished(this@WheelView)
            }
        })
    }

    private fun animateTo(target: Float, duration: Long, listener: Animator.AnimatorListener?) {
        animation?.cancel()
        val animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
            interpolator = OvershootInterpolator(1.15f)
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

    companion object {
        /** The center disc is the dedicated close target. */
        const val CLOSE_SLOT = 999
    }
}
