package com.denis.spenfix

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notes = NotesStore.loadAll(this)
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val items = if (notes.isEmpty()) {
            listOf("Nessuna nota salvata")
        } else {
            notes.map { "${sdf.format(Date(it.timestamp))} — ${it.text}" }
        }

        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        setContentView(listView)
    }
}
