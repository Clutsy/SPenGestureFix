package com.spengesturefix

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent configuration for the six Air Command actions and its accent.
 * The wheel intentionally has no background-photo setting: a compact,
 * consistent surface is faster and keeps the pen input path unobstructed.
 */
object WheelConfig {
    const val SLOT_COUNT = 7
    /** How many recently used wheel colors are remembered. */
    const val RECENT_COLOR_LIMIT = 8
    private const val PREFS = "spen_wheel"
    private const val KEY_SLOTS = "slots_json"
    private const val KEY_WHEEL_COLOR = "wheel_color"
    private const val KEY_RECENT_COLORS = "recent_wheel_colors"
    private val DEFAULT_WHEEL_COLOR = Color(0xFF29B6F6)

    fun loadSlots(context: Context): List<PenAction> {
        val fallback = defaultSlots(context)
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SLOTS, null)
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

    /** Defaults should be immediately useful; empty slots made the first wheel look broken. */
    private fun defaultSlots(context: Context): List<PenAction> = listOf(
        ActionType.QUICK_NOTE,
        ActionType.SCREENSHOT,
        ActionType.SCREEN_WRITE,
        ActionType.SMART_SELECT,
        ActionType.APP_SEARCH,
        ActionType.TOGGLE_FLASHLIGHT,
        ActionType.TRANSLATE
    ).map { type -> PenAction(type, type.label(context)) }

    fun saveSlot(context: Context, index: Int, action: PenAction) {
        if (index !in 0 until SLOT_COUNT) return
        val slots = loadSlots(context).toMutableList()
        slots[index] = action
        saveSlots(context, slots)
    }

    /** Reorders a slot; used by the dashboard reorder controls. */
    fun moveSlot(context: Context, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in 0 until SLOT_COUNT || toIndex !in 0 until SLOT_COUNT) return
        if (fromIndex == toIndex) return
        val slots = loadSlots(context).toMutableList()
        val item = slots.removeAt(fromIndex)
        slots.add(toIndex, item)
        saveSlots(context, slots)
    }

    private fun saveSlots(context: Context, slots: List<PenAction>) {
        val array = JSONArray()
        slots.take(SLOT_COUNT).forEach {
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

    fun getWheelColor(context: Context): Color {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WHEEL_COLOR, DEFAULT_WHEEL_COLOR.toArgb())
        return Color(stored)
    }

    fun setWheelColor(context: Context, color: Color) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WHEEL_COLOR, color.toArgb())
            .apply()
        rememberWheelColor(context, color)
    }

    /** Recently applied wheel colors, newest first (empty until one is picked). */
    fun getRecentWheelColors(context: Context): List<Color> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_COLORS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                runCatching { Color(array.getInt(index)) }.getOrNull()
            }.take(RECENT_COLOR_LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Records [color] as recently used: moves it to the front when it is
     * already known, trims the list to [RECENT_COLOR_LIMIT]. Called together
     * with [setWheelColor] so every applied color stays reachable.
     */
    fun rememberWheelColor(context: Context, color: Color) {
        val updated = (listOf(color) + getRecentWheelColors(context).filter { it != color })
            .take(RECENT_COLOR_LIMIT)
        val array = JSONArray()
        updated.forEach { array.put(it.toArgb()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT_COLORS, array.toString())
            .apply()
    }
}
