package com.denis.spenfix

import android.content.Context
import android.content.Intent

/** Physical S Pen state reported by the Note 3 presence switch. */
enum class PenPresenceState {
    UNKNOWN,
    INSERTED,
    REMOVED
}

object PenRuntimeState {
    const val ACTION_STATUS = "com.denis.spenfix.RUNTIME_STATUS"
    const val EXTRA_SERVICE_ACTIVE = "service_active"
    const val EXTRA_PRESENCE = "presence"
    const val EXTRA_DIGITIZER = "digitizer_active"

    @Volatile var serviceActive: Boolean = false
        private set
    @Volatile var presence: PenPresenceState = PenPresenceState.UNKNOWN
        private set
    @Volatile var digitizerActive: Boolean = false
        private set

    fun publish(
        context: Context,
        serviceActive: Boolean = this.serviceActive,
        presence: PenPresenceState = this.presence,
        digitizerActive: Boolean = this.digitizerActive
    ) {
        this.serviceActive = serviceActive
        this.presence = presence
        this.digitizerActive = digitizerActive
        context.sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SERVICE_ACTIVE, serviceActive)
            putExtra(EXTRA_PRESENCE, presence.name)
            putExtra(EXTRA_DIGITIZER, digitizerActive)
        })
    }
}

object PenPresenceDecoder {
    private val supportedCodes = setOf(
        "001A", "SW_001A", "SW_PEN_INSERTED", "SW_PEN_IN_SLOT", "SW_PEN"
    )

    fun decode(type: String, code: String, value: String): PenPresenceState? {
        if (type != "EV_SW") return null
        val normalizedCode = code.trim().uppercase()
        if (normalizedCode !in supportedCodes && !normalizedCode.endsWith("001A")) return null

        return when (value.trim().uppercase()) {
            "00000000", "0", "UP", "OK", "CONNECTED", "ATTACHED", "INSERTED", "IN" ->
                PenPresenceState.INSERTED
            "00000001", "1", "DOWN", "NG", "DISCONNECTED", "DETACHED", "REMOVED", "OUT" ->
                PenPresenceState.REMOVED
            else -> null
        }
    }
}
