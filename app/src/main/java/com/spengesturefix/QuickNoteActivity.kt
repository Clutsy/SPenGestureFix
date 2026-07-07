package com.denis.spenfix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Equivalente semplificato di Action Memo: qui è testo scritto (non
 * riconoscimento della scrittura a mano) con un piccolo rilevamento di
 * numeri di telefono per la scorciatoia "Chiama".
 */
class QuickNoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "Scrivi una nota rapida…"
            minLines = 3
            setPadding(32, 32, 32, 32)
        }

        val callButton = Button(this).apply {
            text = "Chiama numero"
            visibility = View.GONE
        }
        val mapsButton = Button(this).apply { text = "Cerca in Maps" }
        val listButton = Button(this).apply { text = "Note salvate" }
        val saveButton = Button(this).apply { text = "Salva" }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val phone = detectPhoneNumber(s?.toString().orEmpty())
                if (phone != null) {
                    callButton.visibility = View.VISIBLE
                    callButton.setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
                } else {
                    callButton.visibility = View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        mapsButton.setOnClickListener {
            val query = Uri.encode(input.text.toString())
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
        }

        listButton.setOnClickListener {
            startActivity(Intent(this, NotesListActivity::class.java))
        }

        saveButton.setOnClickListener {
            NotesStore.save(this, input.text.toString())
            Toast.makeText(this, "Nota salvata", Toast.LENGTH_SHORT).show()
            finish()
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(callButton)
            addView(mapsButton)
            addView(listButton)
            addView(saveButton)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(input)
            addView(actionsRow)
        }
        setContentView(layout)
    }

    private fun detectPhoneNumber(text: String): String? {
        val regex = Regex("[+]?[0-9][0-9 ()-]{6,}[0-9]")
        return regex.find(text)?.value
    }
}
