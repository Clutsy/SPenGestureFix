package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream

/**
 * Samsung-style Scrapbook (ported from SpenCommand): the wheel action first
 * captures the screen, then this collector opens on the fresh capture. The
 * user drags a crop rectangle (or keeps the full shot), optionally attaches
 * a caption that is stored with the notes, and saves into
 * `/sdcard/Pictures/SPenScrapbook/` through the same root copy path the
 * other capture features already use.
 */
class ScrapbookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val bitmap = path?.let(BitmapFactory::decodeFile)
        if (bitmap == null) {
            Toast.makeText(this, R.string.editor_image_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                ScrapbookScreen(
                    bitmap = bitmap,
                    onSave = { cropped, caption -> saveResult(cropped, caption) },
                    onClose = ::finish
                )
            }
        }
    }

    private fun saveResult(bitmap: Bitmap, caption: String) {
        Thread {
            var destination: String? = null
            try {
                val timestamp = System.currentTimeMillis()
                val cacheFile = File(cacheDir, "scrapbook_$timestamp.png")
                FileOutputStream(cacheFile).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                destination = "/sdcard/Pictures/SPenScrapbook/scrapbook_$timestamp.png"
                val process = ProcessBuilder(
                    "su", "-c",
                    "mkdir -p /sdcard/Pictures/SPenScrapbook && cp '${cacheFile.absolutePath}' '$destination'"
                ).start()
                val success = process.waitFor() == 0
                if (success && caption.isNotBlank()) {
                    NotesStore.save(this, caption.trim().take(500))
                }
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

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }
}

@Composable
private fun ScrapbookScreen(
    bitmap: Bitmap,
    onSave: (Bitmap, String) -> Unit,
    onClose: () -> Unit
) {
    var topLeft by remember { mutableStateOf<Offset?>(null) }
    var bottomRight by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var caption by remember { mutableStateOf("") }
    val selectionColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.scrapbook_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .onSizeChanged { canvasSize = it }
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            topLeft = offset
                            bottomRight = offset
                        },
                        onDrag = { change, _ ->
                            bottomRight = change.position
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            val start = topLeft
            val end = bottomRight
            if (start != null && end != null) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val rect = androidx.compose.ui.geometry.Rect(
                        offset = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                        size = Size(
                            kotlin.math.abs(end.x - start.x),
                            kotlin.math.abs(end.y - start.y)
                        )
                    )
                    drawRect(
                        color = selectionColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.scrapbook_caption)) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
            Button(
                onClick = {
                    val start = topLeft
                    val end = bottomRight
                    val cropped = if (
                        start != null && end != null &&
                        canvasSize.width > 0 && canvasSize.height > 0
                    ) {
                        val scaleX = bitmap.width.toFloat() / canvasSize.width
                        val scaleY = bitmap.height.toFloat() / canvasSize.height
                        val left = (minOf(start.x, end.x) * scaleX).toInt()
                            .coerceIn(0, bitmap.width - 1)
                        val top = (minOf(start.y, end.y) * scaleY).toInt()
                            .coerceIn(0, bitmap.height - 1)
                        val width = (kotlin.math.abs(end.x - start.x) * scaleX).toInt()
                            .coerceIn(1, bitmap.width - left)
                        val height = (kotlin.math.abs(end.y - start.y) * scaleY).toInt()
                            .coerceIn(1, bitmap.height - top)
                        Bitmap.createBitmap(bitmap, left, top, width, height)
                    } else {
                        bitmap
                    }
                    onSave(cropped, caption)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.scrapbook_save))
            }
        }
    }
}
