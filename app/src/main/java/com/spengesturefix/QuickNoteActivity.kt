package com.spengesturefix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class QuickNoteActivity : ComponentActivity() {
    private var noteId: Int = -1
    private var noteTimestamp: Long = -1L
    private var initialText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
        noteTimestamp = intent.getLongExtra(EXTRA_NOTE_TIMESTAMP, -1L)
        val existing = when {
            noteTimestamp > 0L -> NotesStore.loadAll(this)
                .firstOrNull { it.timestamp == noteTimestamp }
            noteId >= 0 -> NotesStore.loadAll(this).getOrNull(noteId)
            else -> null
        }
        initialText = existing?.text.orEmpty()

        setContent {
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                QuickNoteComposeScreen(
                    initialText = initialText,
                    editing = noteTimestamp > 0L || noteId >= 0,
                    pinned = existing?.pinned ?: false,
                    colorIndex = existing?.colorIndex ?: -1,
                    onPinnedChanged = { pinned ->
                        noteTimestamp.takeIf { it > 0L }?.let { ts ->
                            NotesStore.setPinned(this, ts, pinned)
                        }
                    },
                    onColorChanged = { colorIndex ->
                        noteTimestamp.takeIf { it > 0L }?.let { ts ->
                            NotesStore.setColor(this, ts, colorIndex)
                        }
                    },
                    onSave = { text ->
                        when {
                            noteTimestamp > 0L -> NotesStore.updateByTimestamp(this, noteTimestamp, text)
                            noteId >= 0 -> NotesStore.update(this, noteId, text)
                            else -> NotesStore.save(this, text)
                        }
                        finish()
                    },
                    onOpenNotes = {
                        startActivity(Intent(this, NotesListActivity::class.java))
                    },
                    onCall = { phone ->
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
                    },
                    onMaps = { query ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))
                    },
                    onOpenLink = { link ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    },
                    onEmail = { address ->
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))
                    },
                    onClose = ::finish
                )
            }
        }
    }

    companion object {
        /** Legacy index extra retained for older callers. */
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TIMESTAMP = "note_timestamp"
    }
}
