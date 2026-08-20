package com.denis.spenfix

import android.content.Context
import org.json.JSONObject

enum class GestureKind { CLICK, DOUBLE_CLICK, LONG_PRESS }

object GestureBindings {
    private const val PREFS = "spen_gesture_bindings"

    fun load(context: Context, gesture: GestureKind): PenAction {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gesture.name, null)
            ?: return defaultFor(context, gesture)
        return try {
            val obj = JSONObject(json)
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
        } catch (e: Exception) {
            defaultFor(context, gesture)
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

    private fun defaultFor(context: Context, gesture: GestureKind): PenAction = when (gesture) {
        GestureKind.CLICK -> PenAction(ActionType.SCREENSHOT, ActionType.SCREENSHOT.label(context))
        GestureKind.DOUBLE_CLICK -> PenAction(ActionType.OPEN_WHEEL, ActionType.OPEN_WHEEL.label(context))
        GestureKind.LONG_PRESS -> PenAction(ActionType.SCREEN_WRITE, ActionType.SCREEN_WRITE.label(context))
    }
}
