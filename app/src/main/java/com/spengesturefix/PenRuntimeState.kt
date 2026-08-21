package com.spengesturefix

import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Physical S Pen state reported by the Note 3 presence switch. */
enum class PenPresenceState {
    UNKNOWN,
    INSERTED,
    REMOVED
}

object PenRuntimeState {
    const val ACTION_STATUS = "com.spengesturefix.RUNTIME_STATUS"
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

/**
 * Process-local mode coordinator. Tablet Mode owns the Wacom stream and must
 * be invisible to the normal side-button/air-command behavior.
 */
object TabletModeState {
    @Volatile
    var isActive: Boolean = false
        private set

    private val activeOwners = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun enter() {
        if (activeOwners.incrementAndGet() == 1) update(true)
    }

    fun exit() {
        val owners = activeOwners.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (owners == 0) update(false)
    }

    /** Explicit setter retained for tests and callers that own the full mode lifecycle. */
    fun setActive(active: Boolean) {
        activeOwners.set(if (active) 1 else 0)
        update(active)
    }

    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        listener(isActive)
        return { listeners -= listener }
    }

    private fun update(active: Boolean) {
        if (isActive == active) return
        isActive = active
        listeners.forEach { listener ->
            runCatching { listener(active) }
        }
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
