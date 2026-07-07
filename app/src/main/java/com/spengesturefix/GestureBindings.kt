package com.denis.spenfix

import android.content.Context
import org.json.JSONObject

/** I tre gesti discreti del tasto laterale che si possono riprogrammare. */
enum class GestureKind { CLICK, DOUBLE_CLICK, LONG_PRESS }

/** Salva/carica quale PenAction è assegnata a ciascun gesto del tasto. */
object GestureBindings {
    private const val PREFS = "spen_gesture_bindings"

    fun load(context: Context, gesture: GestureKind): PenAction {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gesture.name, null)
            ?: return defaultFor(gesture)
        return try {
            val obj = JSONObject(json)
            PenAction(ActionType.valueOf(obj.getString("type")), obj.getString("label"), obj.optString("target", ""))
        } catch (e: Exception) {
            defaultFor(gesture)
        }
    }

    fun save(context: Context, gesture: GestureKind, action: PenAction) {
        val obj = JSONObject().apply {
            put("type", action.type.name)
            put("label", action.label)
            put("target", action.target)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(gesture.name, obj.toString())
            .apply()
    }

    // Comportamento di partenza: quello che l'app aveva già prima di essere
    // riprogrammabile, più la pressione lunga come novità.
    private fun defaultFor(gesture: GestureKind): PenAction = when (gesture) {
        GestureKind.CLICK -> PenAction(ActionType.SCREENSHOT, ActionType.SCREENSHOT.defaultLabel)
        GestureKind.DOUBLE_CLICK -> PenAction(ActionType.OPEN_WHEEL, ActionType.OPEN_WHEEL.defaultLabel)
        GestureKind.LONG_PRESS -> PenAction(ActionType.SCREEN_WRITE, ActionType.SCREEN_WRITE.defaultLabel)
    }
}
