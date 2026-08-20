package com.denis.spenfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wheel configuration: how many and which actions are in the slots,
 * and the custom background image (if chosen by the user via the system
 * picker in MainActivity — the URI is made persistent with
 * takePersistableUriPermission, so it remains valid even after a reboot,
 * not just for the session in which it was chosen).
 */
object WheelConfig {
    const val SLOT_COUNT = 6
    private const val PREFS = "spen_wheel"
    private const val KEY_SLOTS = "slots_json"
    private const val KEY_BG_URI = "background_uri"

    fun loadSlots(context: Context): List<PenAction> {
        val fallback = defaultSlots(context)
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SLOTS, null)
            ?: return fallback
        return try {
            val array = JSONArray(json)
            val parsed = (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val obj = array.getJSONObject(index)
                    val type = ActionType.valueOf(obj.getString("type"))
                    val target = obj.optString("target", "")
                    val storedLabel = obj.optString("label", "")
                    val label = when {
                        type.needsAppTarget && storedLabel.isNotBlank() -> storedLabel
                        type.needsTextTarget && target.isNotBlank() ->
                            context.getString(R.string.command_prefix, target.take(24))
                        else -> type.label(context)
                    }
                    PenAction(type, label, target)
                }.getOrNull()
            }.take(SLOT_COUNT)
            (parsed + fallback).take(SLOT_COUNT)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun defaultSlots(context: Context): List<PenAction> =
        List(SLOT_COUNT) { PenAction(ActionType.NONE, ActionType.NONE.label(context)) }

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
