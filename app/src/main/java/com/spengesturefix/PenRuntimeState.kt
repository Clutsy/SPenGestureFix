package com.spengesturefix

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Physical S Pen state reported by the presence switch. */
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

/** Shared pen activity clock used by both the normal and tablet readers. */
object PenInputActivity {
    private val lastInputAt = AtomicLong(0L)

    fun mark() {
        lastInputAt.set(SystemClock.elapsedRealtime())
    }

    fun lastInputAt(): Long = lastInputAt.get()
}

/**
 * Process-local mode coordinator. Tablet Mode owns the Wacom stream and must
 * be invisible to the normal side-button/Air Command behavior.
 */
object TabletModeState {
    @Volatile
    var isActive: Boolean = false
        private set

    private val activeOwners = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val normalInputRelease = AtomicReference<CountDownLatch?>(null)
    private val normalInputOwner = AtomicBoolean(false)

    fun enter() {
        if (activeOwners.incrementAndGet() == 1) {
            normalInputRelease.set(CountDownLatch(1))
            update(true)
        }
    }

    fun exit() {
        val owners = activeOwners.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (owners == 0) {
            normalInputRelease.set(null)
            update(false)
        }
    }

    /** Explicit setter retained for tests and callers that own the full mode lifecycle. */
    fun setActive(active: Boolean) {
        activeOwners.set(if (active) 1 else 0)
        if (active) normalInputRelease.set(CountDownLatch(1))
        else normalInputRelease.set(null)
        update(active)
    }

    /** Called by the service when it owns the normal reader lifecycle. */
    fun setNormalInputOwner(owned: Boolean) {
        normalInputOwner.set(owned)
        if (!owned) normalInputRelease.get()?.countDown()
    }

    /** Called by the service after its normal digitizer reader has stopped. */
    fun markNormalInputReleased() {
        normalInputRelease.get()?.countDown()
    }

    /**
     * Tablet capture is started from a worker thread. Waiting here prevents a
     * second getevent process from opening the shared Wacom node during handoff.
     */
    fun awaitNormalInputReleased(timeoutMs: Long): Boolean {
        if (!normalInputOwner.get()) return true
        return normalInputRelease.get()
            ?.await(timeoutMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
            ?: true
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
