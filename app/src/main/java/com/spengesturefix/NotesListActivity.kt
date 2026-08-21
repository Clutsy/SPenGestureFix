package com.spengesturefix

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class NotesListActivity : ComponentActivity() {
    private var notes by mutableStateOf<List<QuickNote>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadNotes()
        setContent {
            SpenFixTheme {
                NotesListComposeScreen(
                    notes = notes,
                    onNew = { startActivity(Intent(this, QuickNoteActivity::class.java)) },
                    onEdit = { note ->
                        startActivity(Intent(this, QuickNoteActivity::class.java).apply {
                            putExtra(QuickNoteActivity.EXTRA_NOTE_TIMESTAMP, note.timestamp)
                        })
                    },
                    onDelete = { note ->
                        NotesStore.deleteByTimestamp(this, note.timestamp)
                        loadNotes()
                    },
                    onShare = ::shareNote,
                    onCopy = ::copyNote,
                    onClose = ::finish
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadNotes()
    }

    private fun loadNotes() {
        notes = NotesStore.loadAll(this)
    }

    private fun shareNote(note: QuickNote) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, note.text)
        }, getString(R.string.notes_share)))
    }

    private fun copyNote(note: QuickNote) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_notes), note.text))
    }
}
