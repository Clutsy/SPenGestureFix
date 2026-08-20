package com.denis.spenfix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data class representing a quick note.
 * @property text the note content
 * @property timestamp the creation/last modification time in milliseconds
 */
data class QuickNote(val text: String, val timestamp: Long)

/**
 * Singleton for persisting quick notes using SharedPreferences and JSON.
 * Notes are stored as a JSON array of objects: { "text": "...", "ts": ... }.
 *
 * The list is maintained in **reverse chronological order** (newest first)
 * when using [save] – a new note is prepended.
 */
object NotesStore {
    private const val PREFS = "spen_notes"
    private const val KEY = "notes_json"

    /**
     * Saves a new note from plain text.
     * The note is prepended to the list (newest first).
     * @param text the note content; empty/blank notes are ignored
     */
    fun save(context: Context, text: String) {
        if (text.isBlank()) return
        val notes = loadAll(context).toMutableList()
        notes.add(0, QuickNote(text, System.currentTimeMillis()))
        writeAll(context, notes)
    }

    /**
     * Saves a new note from a [QuickNote] object.
     * The note is prepended (newest first).
     * @param note the complete note to save (text and timestamp are used)
     */
    fun save(context: Context, note: QuickNote) {
        val notes = loadAll(context).toMutableList()
        notes.add(0, note)
        writeAll(context, notes)
    }

    /**
     * Loads all stored notes.
     * @return a list of [QuickNote] in storage order (newest first)
     */
    fun loadAll(context: Context): List<QuickNote> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                QuickNote(obj.getString("text"), obj.getLong("ts"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Updates the note at the given index with new text.
     * The timestamp is updated to the current time.
     * The note stays at the same position in the list.
     * @param index the position of the note to update (0‑based)
     * @param newText the new text content
     */
    fun update(context: Context, index: Int, newText: String) {
        if (newText.isBlank()) return
        val notes = loadAll(context).toMutableList()
        if (index !in notes.indices) return
        val old = notes[index]
        notes[index] = old.copy(text = newText, timestamp = System.currentTimeMillis())
        writeAll(context, notes)
    }

    /**
     * Deletes the note at the specified index.
     * @param index the position of the note to remove
     */
    fun delete(context: Context, index: Int) {
        val notes = loadAll(context).toMutableList()
        if (index !in notes.indices) return
        notes.removeAt(index)
        writeAll(context, notes)
    }

    /**
     * Removes all stored notes completely.
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * Internal helper: writes the entire list to SharedPreferences as a JSON array.
     */
    private fun writeAll(context: Context, notes: List<QuickNote>) {
        val array = JSONArray()
        notes.forEach {
            array.put(JSONObject().apply {
                put("text", it.text)
                put("ts", it.timestamp)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}