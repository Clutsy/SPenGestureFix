package com.denis.spenfix

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A stroke stored in viewport coordinates; it is mapped to the bitmap on save. */
data class ComposeStroke(val points: List<Offset>, val color: Color)

@Composable
fun DrawingSurface(
    bitmap: Bitmap,
    color: Color,
    strokes: List<ComposeStroke>,
    onStrokesChanged: (List<ComposeStroke>) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(modifier = modifier.background(Color.Black)) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(color) {
                    detectDragGestures(
                        onDragStart = { currentPoints = listOf(it) },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints = currentPoints + change.position
                        },
                        onDragEnd = {
                            if (currentPoints.size > 1) {
                                onStrokesChanged(strokes + ComposeStroke(currentPoints, color))
                            }
                            currentPoints = emptyList()
                        },
                        onDragCancel = { currentPoints = emptyList() }
                    )
                }
        ) {
            val allStrokes = strokes + listOfNotNull(
                currentPoints.takeIf { it.size > 1 }?.let { ComposeStroke(it, color) }
            )
            allStrokes.forEach { stroke ->
                val path = Path().apply {
                    stroke.points.firstOrNull()?.let { moveTo(it.x, it.y) }
                    stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = stroke.color,
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun SelectionSurface(
    bitmap: Bitmap,
    selection: Rect?,
    onSelectionChanged: (Rect?) -> Unit,
    modifier: Modifier = Modifier
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    Box(modifier = modifier.background(Color.Black)) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start = it; current = it },
                        onDrag = { change, _ -> change.consume(); current = change.position },
                        onDragEnd = {
                            val a = start
                            val b = current
                            if (a != null && b != null) onSelectionChanged(Rect(a, b))
                            start = null
                            current = null
                        },
                        onDragCancel = { start = null; current = null }
                    )
                }
        ) {
            val rect = selection ?: if (start != null && current != null) {
                Rect(start!!, current!!)
            } else null
            rect?.let {
                drawRect(Color(0x4429B6F6), topLeft = it.topLeft, size = it.size)
                drawRect(Color(0xFF63B8FF), topLeft = it.topLeft, size = it.size, style = Stroke(3f))
            }
        }
    }
}

@Composable
fun TabletPreviewCanvas(
    frame: TabletFrame,
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.background(Color.Black)) {
        if (showGrid) {
            val spacing = 60f
            var x = 0f
            while (x < size.width) {
                drawLine(Color(0xFF171721), Offset(x, 0f), Offset(x, size.height))
                x += spacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0xFF171721), Offset(0f, y), Offset(size.width, y))
                y += spacing
            }
        }
        if (frame.inRange) {
            val point = Offset(frame.x * size.width, frame.y * size.height)
            val radius = 10f + frame.pressure * 40f
            drawCircle(
                color = if (frame.touching) Color(0x4403DAC6) else Color(0x226C63FF),
                radius = radius,
                center = point
            )
            drawCircle(
                color = if (frame.touching) Color(0xFF03DAC6) else Color(0xFFB9B3FF),
                radius = 6f
            )
            drawLine(Color.White.copy(alpha = .55f), point - Offset(20f, 0f), point + Offset(20f, 0f))
            drawLine(Color.White.copy(alpha = .55f), point - Offset(0f, 20f), point + Offset(0f, 20f))
        }
    }
}

@Composable
fun PressureCurvePreview(
    curve: PressureCurveType,
    customPoints: List<Float>,
    modifier: Modifier = Modifier,
    onCustomPointsChanged: (List<Float>) -> Unit = {}
) {
    var activePoint by remember { mutableStateOf(-1) }
    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(curve, customPoints) {
                detectDragGestures(
                    onDragStart = { position ->
                        if (curve != PressureCurveType.CUSTOM) return@detectDragGestures
                        val plotWidth = (size.width - 48f).coerceAtLeast(1f)
                        val plotHeight = (size.height - 48f).coerceAtLeast(1f)
                        var nearest = -1
                        var distance = 28f
                        for (index in customPoints.indices step 2) {
                            val pointX = 24f + customPoints[index] * plotWidth
                            val pointY = 24f + plotHeight - customPoints[index + 1] * plotHeight
                            val candidate = hypot(position.x - pointX, position.y - pointY)
                            if (candidate < distance) {
                                distance = candidate
                                nearest = index
                            }
                        }
                        activePoint = nearest
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (activePoint < 0 || curve != PressureCurveType.CUSTOM) return@detectDragGestures
                        val plotWidth = (size.width - 48f).coerceAtLeast(1f)
                        val plotHeight = (size.height - 48f).coerceAtLeast(1f)
                        val updated = customPoints.toMutableList()
                        if (activePoint > 0) {
                            updated[activePoint] = ((change.position.x - 24f) / plotWidth).coerceIn(0f, 1f)
                        }
                        updated[activePoint + 1] =
                            (1f - (change.position.y - 24f) / plotHeight).coerceIn(0f, 1f)
                        onCustomPointsChanged(updated)
                    },
                    onDragEnd = { activePoint = -1 },
                    onDragCancel = { activePoint = -1 }
                )
            }
    ) {
        val padding = 24f
        val width = (size.width - padding * 2).coerceAtLeast(1f)
        val height = (size.height - padding * 2).coerceAtLeast(1f)
        drawRect(Color(0xFF2B2B34), topLeft = Offset(padding, padding), size = Size(width, height), style = Stroke(1f))
        drawLine(Color(0xFF56565F), Offset(padding, padding + height), Offset(padding + width, padding))
        val path = Path()
        repeat(101) { index ->
            val input = index / 100f
            val output = PressureCurve.apply(input, curve, customPoints)
            val point = Offset(padding + input * width, padding + height - output * height)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, Color(0xFFB9B3FF), style = Stroke(width = 4f))
        if (curve == PressureCurveType.CUSTOM) {
            for (index in customPoints.indices step 2) {
                drawCircle(
                    color = Color(0xFF86D8CC),
                    radius = 7f,
                    center = Offset(
                        padding + customPoints[index] * width,
                        padding + height - customPoints[index + 1] * height
                    )
                )
            }
        }
    }
}

/** Maps Compose viewport strokes onto the fitted bitmap and returns a PNG-ready bitmap. */
fun flattenComposeStrokes(
    source: Bitmap,
    strokes: List<ComposeStroke>,
    viewportWidth: Int,
    viewportHeight: Int
): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    if (viewportWidth <= 0 || viewportHeight <= 0) return result
    val scale = min(viewportWidth.toFloat() / source.width, viewportHeight.toFloat() / source.height)
    if (scale <= 0f) return result
    val fittedWidth = source.width * scale
    val fittedHeight = source.height * scale
    val offsetX = (viewportWidth - fittedWidth) / 2f
    val offsetY = (viewportHeight - fittedHeight) / 2f
    val canvas = AndroidCanvas(result)
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val path = AndroidPath()
        stroke.points.forEachIndexed { index, point ->
            val x = ((point.x - offsetX) / scale).coerceIn(0f, source.width.toFloat())
            val y = ((point.y - offsetY) / scale).coerceIn(0f, source.height.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.STROKE
            color = stroke.color.toArgb()
            strokeWidth = 8f / scale
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
        })
    }
    return result
}

fun mapSelectionToBitmap(
    selection: Rect,
    source: Bitmap,
    viewportWidth: Int,
    viewportHeight: Int
): android.graphics.Rect? {
    if (viewportWidth <= 0 || viewportHeight <= 0) return null
    val scale = min(viewportWidth.toFloat() / source.width, viewportHeight.toFloat() / source.height)
    if (scale <= 0f) return null
    val offsetX = (viewportWidth - source.width * scale) / 2f
    val offsetY = (viewportHeight - source.height * scale) / 2f
    val left = ((selection.left - offsetX) / scale).toInt().coerceIn(0, source.width)
    val top = ((selection.top - offsetY) / scale).toInt().coerceIn(0, source.height)
    val right = ((selection.right - offsetX) / scale).toInt().coerceIn(0, source.width)
    val bottom = ((selection.bottom - offsetY) / scale).toInt().coerceIn(0, source.height)
    return android.graphics.Rect(left, top, right, bottom).takeIf { it.width() > 1 && it.height() > 1 }
}
