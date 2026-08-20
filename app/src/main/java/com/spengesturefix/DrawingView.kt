package com.denis.spenfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** Free drawing canvas (finger or stylus) to annotate a screenshot. */
class DrawingView(context: Context) : View(context) {

    var currentColor: Int = Color.RED
    private val paths = mutableListOf<Pair<Path, Int>>()
    private var currentPath = Path()

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(event.x, event.y) }
                paths.add(currentPath to currentColor)
            }
            MotionEvent.ACTION_MOVE -> currentPath.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((path, color) in paths) {
            paint.color = color
            canvas.drawPath(path, paint)
        }
    }

    fun undo() {
        if (paths.isNotEmpty()) paths.removeAt(paths.size - 1)
        invalidate()
    }

    /** Redraws all strokes on top of the original bitmap and returns the result. */
    fun flattenOnto(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        for ((path, color) in paths) {
            paint.color = color
            canvas.drawPath(path, paint)
        }
        return result
    }
}
