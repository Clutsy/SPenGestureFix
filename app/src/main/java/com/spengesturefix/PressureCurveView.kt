package com.denis.spenfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class PressureCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var curveType: PressureCurveType = PressureCurveType.LINEAR
    private var customPoints = mutableListOf<Float>(0f, 0f, 0.5f, 0.5f, 1f, 1f)
    private var onCurveChanged: ((List<Float>) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C63FF")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#03DAC6")
        style = Paint.Style.FILL
    }

    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 24f
    }

    private val path = Path()
    private var activePointIndex = -1
    private val padding = 40f

    fun setCurveType(type: PressureCurveType) {
        this.curveType = type
        invalidate()
    }

    fun setCustomPoints(points: List<Float>) {
        this.customPoints = points.toMutableList()
        invalidate()
    }

    fun getCustomPoints(): List<Float> = customPoints

    fun setOnCurveChangedListener(listener: (List<Float>) -> Unit) {
        this.onCurveChanged = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat() - 2 * padding
        val h = height.toFloat() - 2 * padding

        // Draw grid
        canvas.drawRect(padding, padding, padding + w, padding + h, gridPaint)
        // Horizontal lines
        for (i in 1..3) {
            val y = padding + (h * i / 4f)
            canvas.drawLine(padding, y, padding + w, y, gridPaint)
            val x = padding + (w * i / 4f)
            canvas.drawLine(x, padding, x, padding + h, gridPaint)
        }

        // Draw diagonal reference
        canvas.drawLine(padding, padding + h, padding + w, padding, diagonalPaint)

        // Draw labels
        canvas.drawText("0", padding - 20f, padding + h + 30f, textPaint)
        canvas.drawText("1", padding + w + 10f, padding - 10f, textPaint)
        canvas.drawText(context.getString(R.string.tablet_pressure), padding, padding - 15f, textPaint)

        // Plot curve
        path.reset()
        val steps = 100
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val outVal = PressureCurve.apply(t, curveType, customPoints)
            val px = padding + t * w
            val py = padding + h - outVal * h
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
        }
        canvas.drawPath(path, curvePaint)

        // Draw custom control points if custom mode is enabled
        if (curveType == PressureCurveType.CUSTOM) {
            for (i in 0 until customPoints.size step 2) {
                val px = padding + customPoints[i] * w
                val py = padding + h - customPoints[i + 1] * h
                // Draw glow/point
                canvas.drawCircle(px, py, 14f, pointPaint)
                canvas.drawCircle(px, py, 14f, pointStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (curveType != PressureCurveType.CUSTOM) return false

        val w = width.toFloat() - 2 * padding
        val h = height.toFloat() - 2 * padding

        val tx = (event.x - padding) / w
        val ty = 1f - (event.y - padding) / h

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activePointIndex = -1
                var minDist = 40f // pixel threshold for touch
                for (i in 0 until customPoints.size step 2) {
                    val px = padding + customPoints[i] * w
                    val py = padding + h - customPoints[i + 1] * h
                    val d = hypot(event.x - px, event.y - py)
                    if (d < minDist) {
                        minDist = d
                        activePointIndex = i
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointIndex != -1) {
                    // Update point coordinates
                    val nx = tx.coerceIn(0f, 1f)
                    val ny = ty.coerceIn(0f, 1f)
                    
                    // Don't drag the boundary points (x=0 and x=1) off their x-boundaries
                    if (activePointIndex == 0) {
                        customPoints[1] = ny
                    } else if (activePointIndex == customPoints.size - 2) {
                        customPoints[activePointIndex + 1] = ny
                    } else {
                        customPoints[activePointIndex] = nx
                        customPoints[activePointIndex + 1] = ny
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointIndex != -1) {
                    onCurveChanged?.invoke(customPoints)
                    activePointIndex = -1
                }
            }
        }
        return true
    }
}
