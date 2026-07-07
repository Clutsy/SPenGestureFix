package com.denis.spenfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configurazione della ruota: quante/quali azioni ci sono negli spicchi,
 * e l'immagine di sfondo personalizzata (se scelta dall'utente tramite
 * il selettore di sistema in MainActivity — l'URI viene reso persistente
 * con takePersistableUriPermission, quindi resta valido anche dopo un
 * riavvio, non solo per la sessione in cui è stata scelta).
 */
object WheelConfig {
    const val SLOT_COUNT = 6
    private const val PREFS = "spen_wheel"
    private const val KEY_SLOTS = "slots_json"
    private const val KEY_BG_URI = "background_uri"

    fun loadSlots(context: Context): List<PenAction> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SLOTS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                PenAction(ActionType.valueOf(obj.getString("type")), obj.getString("label"), obj.optString("target", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSlot(context: Context, index: Int, action: PenAction) {
        val slots = loadSlots(context).toMutableList()
        while (slots.size <= index) slots.add(PenAction(ActionType.NONE, ActionType.NONE.label(context)))
        slots[index] = action
        saveSlots(context, slots)
    }

    private fun saveSlots(context: Context, slots: List<PenAction>) {
        val array = JSONArray()
        slots.forEach {
            array.put(JSONObject().apply {
                put("type", it.type.name)
                put("label", it.label)
                put("target", it.target)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SLOTS, array.toString())
            .apply()
    }

    fun saveBackgroundUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BG_URI, uri.toString())
            .apply()
    }

    fun clearBackground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_BG_URI).apply()
    }

    fun hasBackground(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_BG_URI)

    fun loadBackgroundBitmap(context: Context): Bitmap? {
        val uriString = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BG_URI, null)
            ?: return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}
