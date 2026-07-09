package com.denis.spenfix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.max

/**
 * Radial wheel overlay for quick S Pen actions.
 * Renders a compact, elegant circular menu with a frosted-glass feel:
 *   - Small overall footprint (fits near the pen tip)
 *   - Smooth fade/scale entrance animation
 *   - Slot highlight follows touch position
 *   - Center dismiss zone
 */
class WheelOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var wheelView: WheelView? = null
    private var isVisible = false

    fun toggle() {
        if (isVisible) dismiss() else show()
    }

    fun show() {
        if (isVisible) return
        val slots = WheelConfig.loadSlots(context)
        val background = WheelConfig.loadBackgroundBitmap(context)

        val view = WheelView(
            context = context,
            slots = slots,
            background = background,
            onSlotTapped = { action -> ActionExecutor.execute(context, action, this); dismiss() },
            onDismiss = { dismiss() }
        )

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.35f
        }

        try {
            windowManager.addView(view, params)
            wheelView = view
            isVisible = true
            view.startEnterAnimation()
        } catch (_: Exception) {
            // Overlay permission missing
        }
    }

    fun dismiss() {
        wheelView?.let {
            it.startExitAnimation {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
        }
        wheelView = null
        isVisible = false
    }
}

private class WheelView(
    context: Context,
    private val slots: List<PenAction>,
    private val background: Bitmap?,
    private val onSlotTapped: (PenAction) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    // --- Paints ---
    private val scrimPaint = Paint().apply {
        isAntiAlias = true
        color = Color.TRANSPARENT
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#59000000") }
    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#28FFFFFF")
    }
    private val centerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6121216")
    }
    private val centerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1FFFFFFF")
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC1B1B22")
    }
    private val slotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#16FFFFFF")
    }
    private val slotActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF7C73FF")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDEDF0")
        textAlign = Paint.Align.CENTER
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7C73FF")
        textAlign = Paint.Align.CENTER
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E95")
        textAlign = Paint.Align.CENTER
        textSize = 26f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    // Filled path paints for slot pie segments
    private val piePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10101400")
    }

    private var centerX = 0f
    private var centerY = 0f

    // Sizing - smaller and elegant
    private val radiusDp = 88f   // distance of slot centers from center
    private var radius = 0f
    private var slotRadius = 0f  // computed
    private val closeRadiusDp = 26f
    private var closeRadius = 0f

    // Animation progress 0..1 (enter), 1..0 (exit)
    private var progress = 0f
    private var animator: ValueAnimator? = null

    // Touch highlight
    private var activeSlot = -1

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = dp(radiusDp)
        slotRadius = dp(38f)
        closeRadius = dp(closeRadiusDp)
    }

    fun startEnterAnimation() {
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { v -> progress = v.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun startExitAnimation(onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { v -> progress = v.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
            })
            start()
        }
    }

    private fun slotCenter(i: Int, count: Int): Pair<Float, Float> {
        val angle = (2 * Math.PI * i / count) - Math.PI / 2
        return Pair(
            centerX + (radius * progress) * cos(angle).toFloat(),
            centerY + (radius * progress) * sin(angle).toFloat()
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Dim scrim
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Background image (centered, blurred-feel circle clip)
        if (background != null) {
            val src = android.graphics.Rect(0, 0, background.width, background.height)
            val dst = android.graphics.RectF(
                centerX - (radius + slotRadius + dp(10f)) * progress,
                centerY - (radius + slotRadius + dp(10f)) * progress,
                centerX + (radius + slotRadius + dp(10f)) * progress,
                centerY + (radius + slotRadius + dp(10f)) * progress
            )
            canvas.save()
            canvas.drawBitmap(background, src, dst, null)
            // Soft overlay tint
            canvas.drawRect(dst, dimPaint)
            canvas.restore()
        }

        // Faint outer ring
        canvas.drawCircle(centerX, centerY, (radius + slotRadius * 0.5f) * progress, ringStrokePaint)

        val count = slots.size.coerceAtLeast(1)
        if (slots.isNotEmpty()) {
            slots.forEachIndexed { i, action ->
                val (sx, sy) = slotCenter(i, count)
                val r = slotRadius * progress

                // Glow when active
                if (activeSlot == i) {
                    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#337C73FF")
                    }
                    canvas.drawCircle(sx, sy, r + dp(6f), glow)
                    canvas.drawCircle(sx, sy, r, slotActivePaint)
                } else {
                    canvas.drawCircle(sx, sy, r, slotPaint)
                    canvas.drawCircle(sx, sy, r, slotStrokePaint)
                }

                // Letter mark: first 1–2 letters of the label
                val mark = labelMark(action.label)
                textPaint.textSize = 16f * max(0.6f, progress)
                canvas.drawText(mark, sx, sy + 6f * progress, textPaint)
            }
        } else {
            secondaryTextPaint.textSize = 16f * progress
            canvas.drawText(context.getString(R.string.wheel_no_slots), centerX, centerY - radius * progress - dp(30f), secondaryTextPaint)
        }

        // Center disc (close button)
        val cr = closeRadius * progress
        canvas.drawCircle(centerX, centerY, cr, centerDiscPaint)
        canvas.drawCircle(centerX, centerY, cr, centerStrokePaint)
        closeTextPaint.textSize = 22f * max(0.6f, progress)
        canvas.drawText("×", centerX, centerY + 7f * progress, closeTextPaint)
    }

    private fun labelMark(label: String): String {
        // Take first significant word's first letters (max 2)
        val parts = label.trim().split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return (parts[0].take(1) + parts[1].take(1)).uppercase()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                updateActiveSlot(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateActiveSlot(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                if (hypot(dx, dy) < closeRadius) {
                    onDismiss()
                    return true
                }
                if (slots.isNotEmpty()) {
                    val idx = activeSlot
                    activeSlot = -1
                    invalidate()
                    if (idx in slots.indices) {
                        onSlotTapped(slots[idx])
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeSlot = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateActiveSlot(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        if (slots.isEmpty()) return
        val count = slots.size.coerceAtLeast(1)
        var newActive = -1
        slots.forEachIndexed { i, _ ->
            val (sxActual, syActual) = slotCenter(i, count)
            if (hypot(dx - (sxActual - centerX), dy - (syActual - centerY)) < slotRadius) {
                newActive = i
            }
        }
        if (newActive != activeSlot) {
            activeSlot = newActive
            invalidate()
        }
    }
}
