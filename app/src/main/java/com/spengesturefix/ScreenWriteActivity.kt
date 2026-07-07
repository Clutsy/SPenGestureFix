package com.denis.spenfix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/** Screenshot già catturato da ActionExecutor prima di aprire questa activity, qui si annota. */
class ScreenWriteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    private lateinit var drawingView: DrawingView
    private lateinit var sourceBitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val bitmap = path?.let { BitmapFactory.decodeFile(it) }
        if (bitmap == null) {
            Toast.makeText(this, "Screenshot non trovato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceBitmap = bitmap

        val root = FrameLayout(this)
        val imageView = ImageView(this).apply { setImageBitmap(sourceBitmap) }
        drawingView = DrawingView(this)

        root.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(drawingView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(buildToolbar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 48
        })

        setContentView(root)
    }

    private fun buildToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#DD222222"))

            listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.WHITE, Color.BLACK).forEach { color ->
                addView(Button(context).apply {
                    text = "●"
                    setTextColor(color)
                    setOnClickListener { drawingView.currentColor = color }
                })
            }
            addView(Button(context).apply {
                text = "Annulla"
                setOnClickListener { drawingView.undo() }
            })
            addView(Button(context).apply {
                text = "Salva"
                setOnClickListener { saveResult() }
            })
        }
    }

    private fun saveResult() {
        Thread {
            try {
                val flattened = drawingView.flattenOnto(sourceBitmap)
                val cacheFile = File(cacheDir, "sw_${System.currentTimeMillis()}.png")
                FileOutputStream(cacheFile).use { flattened.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val destPath = "/sdcard/Pictures/SPenScreenshots/screenwrite_${System.currentTimeMillis()}.png"
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${cacheFile.absolutePath}' '$destPath'")).waitFor()

                runOnUiThread {
                    Toast.makeText(this, "Salvato in Pictures/SPenScreenshots", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Errore nel salvataggio", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
