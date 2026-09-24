package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream

/**
 * Off-screen memo (ported from SpenCommand): a black full-screen canvas
 * where the S-Pen (or finger) writes in white, like writing on a blackboard.
 * Kept on top of whatever was on screen so a quick thought never buries the
 * underlying app. Strokes can be undone, the result saves into
 * `/sdcard/Pictures/SPenScreenshots/` via the same root copy path used by
 * the other capture features.
 */
class OffscreenMemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                OffscreenMemoScreen(
                    onSave = ::saveResult,
                    onClose = ::finish
                )
            }
        }
    }

    private fun saveResult(bitmap: Bitmap) {
        Thread {
            try {
                val timestamp = System.currentTimeMillis()
                val cacheFile = File(cacheDir, "memo_$timestamp.png")
                FileOutputStream(cacheFile).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val destination = "/sdcard/Pictures/SPenScreenshots/memo_$timestamp.png"
                val process = ProcessBuilder(
                    "su", "-c",
                    "mkdir -p /sdcard/Pictures/SPenScreenshots && cp '${cacheFile.absolutePath}' '$destination'"
                ).start()
                val success = process.waitFor() == 0
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (success) R.string.editor_saved else R.string.editor_save_failed,
                        if (success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                    if (success) finish()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }
}

private data class MemoStroke(
    val points: MutableList<Offset> = mutableListOf(),
    var width: Float = 4f
)

@Composable
private fun OffscreenMemoScreen(
    onSave: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val strokes = remember { mutableStateListOf<MemoStroke>() }
    var strokeWidth by remember { mutableFloatStateOf(5f) }
    var canvasSizePx by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResourceCompat(R.string.memo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB8BCC8)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(strokeWidth) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                strokes.add(MemoStroke(mutableListOf(offset), strokeWidth))
                            },
                            onDrag = { change, _ ->
                                strokes.lastOrNull()?.points?.add(change.position)
                            }
                        )
                    }
            ) {
                canvasSizePx = Offset(size.width, size.height)
                strokes.forEach { stroke ->
                    if (stroke.points.size < 2) {
                        stroke.points.firstOrNull()?.let { point ->
                            drawCircle(
                                color = Color.White,
                                radius = stroke.width / 2f,
                                center = point
                            )
                        }
                        return@forEach
                    }
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(stroke.points.first().x, stroke.points.first().y)
                    stroke.points.drop(1).forEach { point ->
                        path.lineTo(point.x, point.y)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(
                            width = stroke.width,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResourceCompat(R.string.memo_pen_size),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB8BCC8)
            )
            Slider(
                value = strokeWidth,
                onValueChange = { strokeWidth = it },
                valueRange = 2f..18f,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { strokes.clear() },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResourceCompat(R.string.memo_clear), color = Color.White)
            }
            OutlinedButton(
                onClick = { strokes.removeLastOrNull() },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResourceCompat(R.string.memo_undo), color = Color.White)
            }
            Button(
                onClick = {
                    if (canvasSizePx.x <= 0f || canvasSizePx.y <= 0f) return@Button
                    val bitmap = Bitmap.createBitmap(
                        canvasSizePx.x.toInt().coerceAtLeast(1),
                        canvasSizePx.y.toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        isAntiAlias = true
                    }
                    strokes.forEach { stroke ->
                        if (stroke.points.size < 2) {
                            stroke.points.firstOrNull()?.let { point ->
                                paint.style = android.graphics.Paint.Style.FILL
                                canvas.drawCircle(point.x, point.y, stroke.width / 2f, paint)
                                paint.style = android.graphics.Paint.Style.STROKE
                            }
                            return@forEach
                        }
                        val path = android.graphics.Path()
                        path.moveTo(stroke.points.first().x, stroke.points.first().y)
                        stroke.points.drop(1).forEach { point ->
                            path.lineTo(point.x, point.y)
                        }
                        paint.strokeWidth = stroke.width
                        canvas.drawPath(path, paint)
                    }
                    onSave(bitmap)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResourceCompat(R.string.memo_save))
            }
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResourceCompat(R.string.dialog_cancel))
        }
    }
}

/** Small indirection keeping the import list short in this file. */
@androidx.compose.runtime.Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
