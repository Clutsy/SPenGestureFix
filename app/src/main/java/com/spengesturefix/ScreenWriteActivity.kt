package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File
import java.io.FileOutputStream

class ScreenWriteActivity : ComponentActivity() {
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
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                ScreenWriteComposeScreen(
                    bitmap = sourceBitmap,
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
                val cacheFile = File(cacheDir, "screenwrite_$timestamp.png")
                FileOutputStream(cacheFile).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val destination = "/sdcard/Pictures/SPenScreenshots/screenwrite_$timestamp.png"
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
