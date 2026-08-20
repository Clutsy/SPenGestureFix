package com.denis.spenfix

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
                    onEdit = { index ->
                        startActivity(Intent(this, QuickNoteActivity::class.java).apply {
                            putExtra(QuickNoteActivity.EXTRA_NOTE_ID, index)
                        })
                    },
                    onDelete = { index ->
                        NotesStore.delete(this, index)
                        loadNotes()
                    },
                    onShare = ::shareNote,
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
}
