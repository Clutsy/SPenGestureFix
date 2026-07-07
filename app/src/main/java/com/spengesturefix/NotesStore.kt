package com.denis.spenfix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class QuickNote(val text: String, val timestamp: Long)

/** Salva le note rapide (Action Memo semplificato) in SharedPreferences come JSON. */
object NotesStore {
    private const val PREFS = "spen_notes"
    private const val KEY = "notes_json"

    fun save(context: Context, text: String) {
        if (text.isBlank()) return
        val notes = loadAll(context).toMutableList()
        notes.add(0, QuickNote(text, System.currentTimeMillis()))
        val array = JSONArray()
        notes.forEach {
            array.put(JSONObject().apply {
                put("text", it.text)
                put("ts", it.timestamp)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun loadAll(context: Context): List<QuickNote> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
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
}
