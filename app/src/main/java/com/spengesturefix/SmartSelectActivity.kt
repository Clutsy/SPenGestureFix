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

/**
 * Equivalente semplificato di Scrapbook/Image Clip: ritaglio rettangolare
 * (non a forma libera come l'originale, per affidabilità) di un'area dello
 * screenshot già catturato.
 */
class SmartSelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    private lateinit var selectionView: SelectionView
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
        selectionView = SelectionView(this)

        root.addView(imageView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(selectionView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

            addView(Button(context).apply {
                text = "Annulla"
                setOnClickListener { finish() }
            })
            addView(Button(context).apply {
                text = "Ritaglia e salva"
                setOnClickListener { saveCrop() }
            })
        }
    }

    private fun saveCrop() {
        val rect = selectionView.getSelectionRect()
        if (rect.width() < 10 || rect.height() < 10) {
            Toast.makeText(this, "Trascina per selezionare un'area", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val safeRect = android.graphics.Rect(
                    rect.left.coerceIn(0, sourceBitmap.width),
                    rect.top.coerceIn(0, sourceBitmap.height),
                    rect.right.coerceIn(0, sourceBitmap.width),
                    rect.bottom.coerceIn(0, sourceBitmap.height)
                )
                val cropped = Bitmap.createBitmap(sourceBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
                val cacheFile = File(cacheDir, "clip_${System.currentTimeMillis()}.png")
                FileOutputStream(cacheFile).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val destPath = "/sdcard/Pictures/SPenScreenshots/clip_${System.currentTimeMillis()}.png"
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${cacheFile.absolutePath}' '$destPath'")).waitFor()

                runOnUiThread {
                    Toast.makeText(this, "Ritaglio salvato in Pictures/SPenScreenshots", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Errore nel salvataggio", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
