package com.spengesturefix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A quick note with a stable timestamp identifier. */
data class QuickNote(val text: String, val timestamp: Long)

/**
 * Small offline note repository backed by SharedPreferences and JSON.
 * The newest note is kept first. Storage is intentionally local and requires
 * no account, database, or network permission.
 */
object NotesStore {
    private const val PREFS = "spen_notes"
    private const val KEY = "notes_json"
    private const val MAX_NOTE_LENGTH = 4_000
    private const val MAX_NOTES = 200

    fun save(context: Context, text: String) {
        val safeText = text.take(MAX_NOTE_LENGTH)
        if (safeText.isBlank()) return
        val notes = loadAll(context).toMutableList()
        notes.add(0, QuickNote(safeText, nextTimestamp(notes)))
        writeAll(context, notes)
    }

    fun save(context: Context, note: QuickNote) {
        val safeText = note.text.take(MAX_NOTE_LENGTH)
        if (safeText.isBlank()) return
        val notes = loadAll(context).toMutableList()
        val requestedTimestamp = note.timestamp.takeIf { it > 0L }
        val timestamp = if (requestedTimestamp != null &&
            notes.none { it.timestamp == requestedTimestamp }
        ) requestedTimestamp else nextTimestamp(notes)
        notes.add(0, QuickNote(safeText, timestamp))
        writeAll(context, notes)
    }

    fun loadAll(context: Context): List<QuickNote> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val seenTimestamps = HashSet<Long>()
            (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val obj = array.getJSONObject(index)
                    val text = obj.optString("text", "").take(MAX_NOTE_LENGTH)
                    val timestamp = obj.optLong("ts", 0L)
                    if (text.isBlank() || timestamp <= 0L || !seenTimestamps.add(timestamp)) null
                    else QuickNote(text, timestamp)
                }.getOrNull()
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            // A corrupt preference must not make the notes screen crash.
            emptyList()
        }
    }

    fun update(context: Context, index: Int, newText: String) {
        val safeText = newText.take(MAX_NOTE_LENGTH)
        if (safeText.isBlank()) return
        val notes = loadAll(context).toMutableList()
        if (index !in notes.indices) return
        val old = notes[index]
        notes[index] = old.copy(
            text = safeText,
            timestamp = nextTimestamp(notes, excluding = old.timestamp)
        )
        writeAll(context, notes)
    }

    fun delete(context: Context, index: Int) {
        val notes = loadAll(context).toMutableList()
        if (index !in notes.indices) return
        notes.removeAt(index)
        writeAll(context, notes)
    }

    /** Updates a note by its stable timestamp, safe after filtering or sorting. */
    fun updateByTimestamp(context: Context, timestamp: Long, newText: String) {
        val index = loadAll(context).indexOfFirst { it.timestamp == timestamp }
        if (index >= 0) update(context, index, newText)
    }

    /** Deletes a note by its stable timestamp, safe after filtering or sorting. */
    fun deleteByTimestamp(context: Context, timestamp: Long) {
        val notes = loadAll(context).toMutableList()
        val index = notes.indexOfFirst { it.timestamp == timestamp }
        if (index < 0) return
        notes.removeAt(index)
        writeAll(context, notes)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    private fun nextTimestamp(notes: List<QuickNote>, excluding: Long? = null): Long {
        var candidate = System.currentTimeMillis()
        while (notes.any { it.timestamp == candidate && it.timestamp != excluding }) {
            candidate++
        }
        return candidate
    }

    private fun writeAll(context: Context, notes: List<QuickNote>) {
        val array = JSONArray()
        notes.sortedByDescending { it.timestamp }.take(MAX_NOTES).forEach { note ->
            array.put(JSONObject().apply {
                put("text", note.text)
                put("ts", note.timestamp)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
