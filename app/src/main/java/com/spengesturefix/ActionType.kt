package com.denis.spenfix

import android.content.Context

enum class ActionType(
    val labelResId: Int,
    val icon: String,                      // emoji/symbol shown in the wheel
    val needsAppTarget: Boolean = false,
    val needsTextTarget: Boolean = false
) {
    NONE           (R.string.action_none,          "✕"),
    LAUNCH_APP     (R.string.action_launch_app,    "🚀", needsAppTarget  = true),
    OPEN_WHEEL     (R.string.action_open_wheel,    "⚙️"),
    SCREENSHOT     (R.string.action_screenshot,    "📷"),
    SCREEN_WRITE   (R.string.action_screen_write,  "✏️"),
    SMART_SELECT   (R.string.action_smart_select,  "✂️"),
    QUICK_NOTE     (R.string.action_quick_note,    "📝"),
    APP_SEARCH     (R.string.action_app_search,    "🔍"),
    PEN_WINDOW     (R.string.action_pen_window,    "🪟", needsAppTarget  = true),
    TOGGLE_FLASHLIGHT(R.string.action_flashlight,  "🔦"),
    TOGGLE_WIFI    (R.string.action_wifi,          "📶"),
    TOGGLE_BLUETOOTH(R.string.action_bluetooth,   "🔵"),
    TOGGLE_MUTE    (R.string.action_mute,          "🔇"),
    LOCK_SCREEN    (R.string.action_lock_screen,   "🔒"),
    CUSTOM_SHELL   (R.string.action_custom_shell,  "⌨️", needsTextTarget = true);

    fun label(context: Context): String = context.getString(labelResId)
}
