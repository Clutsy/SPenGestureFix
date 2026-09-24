package com.spengesturefix

import android.content.Context

enum class ActionType(
    val labelResId: Int,
    /** Vector drawable shown in the wheel and the action pickers. */
    val iconResId: Int,
    val needsAppTarget: Boolean = false,
    val needsTextTarget: Boolean = false
) {
    NONE           (R.string.action_none,          R.drawable.ic_act_none),
    LAUNCH_APP     (R.string.action_launch_app,    R.drawable.ic_act_launch_app, needsAppTarget  = true),
    OPEN_WHEEL     (R.string.action_open_wheel,    R.drawable.ic_act_open_wheel),
    SCREENSHOT     (R.string.action_screenshot,    R.drawable.ic_act_screenshot),
    SCREEN_WRITE   (R.string.action_screen_write,  R.drawable.ic_act_screen_write),
    SMART_SELECT   (R.string.action_smart_select,  R.drawable.ic_act_smart_select),
    QUICK_NOTE     (R.string.action_quick_note,    R.drawable.ic_act_quick_note),
    APP_SEARCH     (R.string.action_app_search,    R.drawable.ic_act_app_search),
    TRANSLATE      (R.string.action_translate,     R.drawable.ic_act_translate),
    PEN_WINDOW     (R.string.action_pen_window,    R.drawable.ic_act_pen_window, needsAppTarget  = true),
    TOGGLE_FLASHLIGHT(R.string.action_flashlight,  R.drawable.ic_act_flashlight),
    TOGGLE_WIFI    (R.string.action_wifi,          R.drawable.ic_act_wifi),
    GO_BACK        (R.string.action_go_back,       R.drawable.ic_act_go_back),
    TOGGLE_BLUETOOTH(R.string.action_bluetooth,    R.drawable.ic_act_bluetooth),
    TOGGLE_MUTE    (R.string.action_mute,          R.drawable.ic_act_mute),
    LOCK_SCREEN    (R.string.action_lock_screen,   R.drawable.ic_act_lock),
    SCRAPBOOK      (R.string.action_scrapbook,     R.drawable.ic_act_scrapbook),
    OFFSCREEN_MEMO (R.string.action_offscreen_memo, R.drawable.ic_act_quick_note),
    GO_HOME        (R.string.action_go_home,       R.drawable.ic_act_go_home),
    OPEN_NOTIFICATIONS (R.string.action_notifications, R.drawable.ic_act_bell),
    RECENT_APPS    (R.string.action_recent_apps,   R.drawable.ic_act_launch_app),
    CUSTOM_SHELL   (R.string.action_custom_shell,  R.drawable.ic_act_shell, needsTextTarget = true);

    fun label(context: Context): String = context.getString(labelResId)
}
