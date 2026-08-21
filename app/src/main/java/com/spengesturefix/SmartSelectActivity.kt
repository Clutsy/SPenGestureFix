package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File
import java.io.FileOutputStream

class SmartSelectActivity : ComponentActivity() {
    private lateinit var sourceBitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val bitmap = path?.let(BitmapFactory::decodeFile)
        if (bitmap == null) {
            Toast.makeText(this, R.string.editor_image_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceBitmap = bitmap
        setContent {
            SpenFixTheme {
                SmartSelectComposeScreen(
                    bitmap = sourceBitmap,
                    onSave = ::saveCrop,
                    onClose = ::finish
                )
            }
        }
    }

    private fun saveCrop(rect: android.graphics.Rect) {
        Thread {
            try {
                val safe = android.graphics.Rect(
                    rect.left.coerceIn(0, sourceBitmap.width - 1),
                    rect.top.coerceIn(0, sourceBitmap.height - 1),
                    rect.right.coerceIn(1, sourceBitmap.width),
                    rect.bottom.coerceIn(1, sourceBitmap.height)
                )
                if (safe.width() <= 1 || safe.height() <= 1) {
                    runOnUiThread { Toast.makeText(this, R.string.editor_selection_empty, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val cropped = Bitmap.createBitmap(sourceBitmap, safe.left, safe.top, safe.width(), safe.height())
                val timestamp = System.currentTimeMillis()
                val cacheFile = File(cacheDir, "clip_$timestamp.png")
                FileOutputStream(cacheFile).use {
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val destination = "/sdcard/Pictures/SPenScreenshots/clip_$timestamp.png"
                val process = ProcessBuilder(
                    "su", "-c",
                    "mkdir -p /sdcard/Pictures/SPenScreenshots && cp '${cacheFile.absolutePath}' '$destination'"
                ).start()
                val success = process.waitFor() == 0
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
                    }
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
