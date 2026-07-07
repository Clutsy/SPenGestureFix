package com.denis.spenfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Menu radiale ("la ruota") che appare sopra le altre app: fino a
 * WheelConfig.SLOT_COUNT scorciatoie disposte in cerchio, con sfondo
 * personalizzabile con una foto scelta in MainActivity.
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(view, params)
            wheelView = view
            isVisible = true
        } catch (_: Exception) {
            // Overlay non concesso: l'utente deve prima usare il pulsante
            // "Concedi permesso overlay" in MainActivity.
        }
    }

    fun dismiss() {
        wheelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
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

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#446C63FF")
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE6C63FF")
    }
    private val slotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#226C63FF")
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD1A1A2E")
    }
    private val scrimPaint = Paint().apply { color = Color.parseColor("#CC000000") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private var centerX = 0f
    private var centerY = 0f
    private val radius = 200f
    private val slotRadius = 52f
    private val closeRadius = 38f

    override fun onDraw(canvas: Canvas) {
        centerX = width / 2f
        centerY = height / 2f

        if (background != null) {
            canvas.drawBitmap(background, Rect(0, 0, background.width, background.height), Rect(0, 0, width, height), null)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        val count = slots.size.coerceAtLeast(1)
        if (slots.isNotEmpty()) {
            slots.forEachIndexed { i, action ->
                val angle = (2 * Math.PI * i / count) - Math.PI / 2
                val sx = centerX + radius * cos(angle).toFloat()
                val sy = centerY + radius * sin(angle).toFloat()

                // Draw a nice glow behind the slot circle
                canvas.drawCircle(sx, sy, slotRadius + 6f, slotGlowPaint)
                // Draw slot circle
                canvas.drawCircle(sx, sy, slotRadius, slotPaint)
                // Draw text
                canvas.drawText(action.label.take(10), sx, sy + 7f, textPaint)
            }
        } else {
            canvas.drawText(context.getString(R.string.wheel_no_slots), centerX, centerY - radius - 40f, textPaint)
        }

        canvas.drawCircle(centerX, centerY, closeRadius, centerPaint)
        canvas.drawText("✕", centerX, centerY + 8f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val dx = event.x - centerX
        val dy = event.y - centerY

        if (hypot(dx, dy) < closeRadius) {
            onDismiss()
            return true
        }

        val count = slots.size.coerceAtLeast(1)
        slots.forEachIndexed { i, action ->
            val angle = (2 * Math.PI * i / count) - Math.PI / 2
            val sx = radius * cos(angle).toFloat()
            val sy = radius * sin(angle).toFloat()
            if (hypot(dx - sx, dy - sy) < slotRadius) {
                onSlotTapped(action)
            }
        }
        return true
    }
}
