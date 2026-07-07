package com.denis.spenfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var showGrid = true
    private var isPenInRange = false
    private var isPenTouching = false
    private var penX = 0f
    private var penY = 0f
    private var penPressure = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#151525")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        strokeWidth = 2f
        style = Paint.Style.FILL
    }

    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#226C63FF")
        style = Paint.Style.FILL
    }

    fun setShowGrid(show: Boolean) {
        this.showGrid = show
        invalidate()
    }

    fun setPointer(x: Float, y: Float, pressure: Float, touching: Boolean, inRange: Boolean) {
        this.penX = x
        this.penY = y
        this.penPressure = pressure
        this.isPenTouching = touching
        this.isPenInRange = inRange
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (showGrid) {
            // Draw a tech grid
            val gridSpacing = 60f
            var x = 0f
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, gridPaint)
                x += gridSpacing
            }
            var y = 0f
            while (y < h) {
                canvas.drawLine(0f, y, w, y, gridPaint)
                y += gridSpacing
            }
        }

        // Draw pen pointer indicator
        if (isPenInRange && w > 0 && h > 0) {
            val px = penX * w
            val py = penY * h

            // Draw pressure indicator ring
            if (penPressure > 0f) {
                val radius = 10f + penPressure * 40f
                hoverPaint.color = if (isPenTouching) Color.parseColor("#4403DAC6") else Color.parseColor("#226C63FF")
                canvas.drawCircle(px, py, radius, hoverPaint)
            }

            // Draw center pointer
            pointerPaint.color = if (isPenTouching) Color.parseColor("#03DAC6") else Color.parseColor("#6C63FF")
            canvas.drawCircle(px, py, 6f, pointerPaint)

            // Draw fine lines/crosshair
            pointerPaint.style = Paint.Style.STROKE
            pointerPaint.strokeWidth = 1f
            canvas.drawLine(px - 20f, py, px + 20f, py, pointerPaint)
            canvas.drawLine(px, py - 20f, px, py + 20f, pointerPaint)
            pointerPaint.style = Paint.Style.FILL
        }
    }
}
