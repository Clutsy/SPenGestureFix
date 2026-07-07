package com.denis.spenfix

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Legge in streaming gli eventi grezzi di un device /dev/input/eventX
 * lanciando "getevent -l <device>" tramite una shell root, e restituisce
 * ogni riga già divisa in (tipo, codice, valore) come stringhe grezze
 * (es. "EV_KEY", "BTN_STYLUS", "DOWN" oppure "EV_ABS", "ABS_DISTANCE", "00000005").
 *
 * Perché parsing testuale e non lettura binaria della struct input_event:
 * la struct cambia dimensione tra ABI a 32 e 64 bit (per via del padding
 * di timeval), quindi il parsing binario andrebbe scritto due volte e
 * verificato per architettura. Il parsing testuale costa qualche
 * millisecondo in più per riga ma è identico ovunque: per gesti basati
 * su click/hover (non per drawing ad alta frequenza) è più che sufficiente.
 */
class EPenInputReader(
    private val devicePath: String,
    private val onEvent: (type: String, code: String, value: String) -> Unit
) {
    companion object {
        private const val TAG = "EPenInputReader"
        // Metti a false una volta calibrato tutto, per non riempire il logcat.
        const val DEBUG_LOG = true
    }

    @Volatile private var running = false
    private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -l \"$devicePath\""))
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    parseLine(line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lettura eventi interrotta per $devicePath", e)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            process?.destroy()
            // Rete di sicurezza: destroy() sul processo "su" a volte non
            // uccide il vero "getevent" figlio. Nota: questo pkill è
            // generico e killerebbe anche altre istanze di getevent -l
            // eventualmente in corso; per un'app mono-utente va bene.
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f 'getevent -l'"))
        } catch (_: Exception) {
        }
    }

    private fun parseLine(line: String) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return

        val type: String
        val code: String
        val value: String
        if (parts[0].startsWith("/dev/")) {
            if (parts.size < 4) return
            type = parts[1]; code = parts[2]; value = parts[3]
        } else {
            if (parts.size < 3) return
            type = parts[0]; code = parts[1]; value = parts[2]
        }

        if (DEBUG_LOG) Log.d("SPenDebug", "$devicePath -> $type $code $value")
        if (type == "EV_SYN") return
        onEvent(type, code, value)
    }
}
