package com.denis.spenfix

/**
 * Ogni tipo di azione eseguibile da un gesto del tasto o da uno spicchio
 * della ruota. `needsAppTarget`/`needsTextTarget` dicono alla UI se, dopo
 * aver scelto questo tipo, serve un passo successivo (scegliere un'app o
 * scrivere un comando).
 */
enum class ActionType(
    val defaultLabel: String,
    val needsAppTarget: Boolean = false,
    val needsTextTarget: Boolean = false
) {
    NONE("Nessuna azione"),
    LAUNCH_APP("Apri app", needsAppTarget = true),
    OPEN_WHEEL("Apri ruota S Pen"),
    SCREENSHOT("Screenshot"),
    SCREEN_WRITE("Screen Write (annota screenshot)"),
    SMART_SELECT("Ritaglia area schermo"),
    QUICK_NOTE("Nota rapida (Action Memo)"),
    APP_SEARCH("Cerca e apri app (S Finder)"),
    PEN_WINDOW("Finestra fluttuante (Pen Window)", needsAppTarget = true),
    TOGGLE_FLASHLIGHT("Torcia"),
    TOGGLE_WIFI("Wi-Fi on/off"),
    TOGGLE_BLUETOOTH("Bluetooth on/off"),
    TOGGLE_MUTE("Silenzia/riattiva volume"),
    LOCK_SCREEN("Blocca schermo"),
    CUSTOM_SHELL("Comando root personalizzato", needsTextTarget = true)
}
