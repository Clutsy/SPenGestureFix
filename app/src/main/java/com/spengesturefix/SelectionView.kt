package com.denis.spenfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** View to draw a selection rectangle by dragging the finger/stylus. */
class SelectionView(context: Context) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#5529B6F6")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#2979FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun getSelectionRect(): Rect = Rect(
        min(startX, endX).toInt(),
        min(startY, endY).toInt(),
        max(startX, endX).toInt(),
        max(startY, endY).toInt()
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; endX = event.x; endY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, strokePaint)
    }
}
