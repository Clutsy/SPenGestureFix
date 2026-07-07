package com.denis.spenfix

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText

/**
 * Usato sia per riprogrammare i 3 gesti del tasto sia per configurare
 * gli spicchi della ruota: mostra prima l'elenco dei tipi di azione,
 * poi un secondo passo solo se il tipo scelto lo richiede.
 */
object ActionPickerDialog {

    fun show(context: Context, onPicked: (PenAction) -> Unit) {
        val types = ActionType.values().toList()
        val labels = types.map { it.defaultLabel }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Scegli un'azione")
            .setItems(labels) { _, which ->
                val type = types[which]
                when {
                    type.needsAppTarget -> AppPicker.pick(context) { entry ->
                        onPicked(PenAction(type, entry.label, entry.packageName))
                    }
                    type.needsTextTarget -> showTextInput(context, type, onPicked)
                    else -> onPicked(PenAction(type, type.defaultLabel))
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showTextInput(context: Context, type: ActionType, onPicked: (PenAction) -> Unit) {
        val input = EditText(context).apply {
            hint = "es. settings put system screen_brightness 255"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(context)
            .setTitle("Comando eseguito come root (su -c \"...\")")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val command = input.text.toString()
                if (command.isNotBlank()) {
                    onPicked(PenAction(type, "Comando: ${command.take(24)}", command))
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
