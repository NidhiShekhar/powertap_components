package com.drivool.iot.powertap.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the one session this phone currently claims.
 *
 * Deliberately single-slot: a phone controls at most one charger at a time, and
 * keeping it to one slot means "do I own a session?" can never be ambiguous.
 * Backed by SharedPreferences so a session survives the app being killed
 * mid-charge — that was the old failure where the phone came back with no
 * transaction id and offered to "reset", abandoning a live session.
 */
object SessionLeaseStore {
    private const val PREFS = "session_lease"
    private const val KEY_LEASE = "lease"

    /** Minimum gap between persisted confirmation timestamps. */
    private const val CONFIRM_WRITE_INTERVAL_MS = 30_000L

    private var appContext: Context? = null

    private val _lease = MutableStateFlow<SessionLease?>(null)
    val lease: StateFlow<SessionLease?> = _lease.asStateFlow()

    /** The lease if it still authorises reconnect and Stop, else null. */
    val open: SessionLease? get() = _lease.value?.takeIf { it.isOpen }

    val hasOpenLease: Boolean get() = open != null

    fun init(context: Context) {
        appContext = context.applicationContext
        _lease.value = SessionLease.fromJson(prefs()?.getString(KEY_LEASE, null))
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Claim a session we are about to request from the charger. */
    fun request(
        transactionId: String,
        deviceId: String,
        bleAddress: String,
        mode: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SessionLease {
        val lease = SessionLease(
            transactionId = transactionId,
            deviceId = deviceId,
            bleAddress = bleAddress,
            mode = mode,
            startedAt = nowMs,
            state = LeaseState.Requested,
            lastConfirmedAt = 0L,
        )
        write(lease)
        return lease
    }

    /**
     * Bind this phone to [transactionId] after *our* Start was accepted under a
     * different id than we proposed, or when reclaiming a session this Firebase
     * account already owns in the cloud after a reinstall.
     */
    fun adopt(
        transactionId: String,
        deviceId: String,
        bleAddress: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SessionLease {
        val lease = SessionLease(
            transactionId = transactionId,
            deviceId = deviceId,
            bleAddress = bleAddress,
            mode = _lease.value?.mode ?: "full",
            startedAt = nowMs,
            state = LeaseState.Active,
            lastConfirmedAt = nowMs,
            adopted = true,
        )
        write(lease)
        return lease
    }

    /**
     * Hardware confirmed this transaction id is alive.
     *
     * Confirmation arrives about once a second while charging, so this only
     * touches disk when the lease state actually changes or the recorded
     * timestamp has aged past [CONFIRM_WRITE_INTERVAL_MS]. Writing (and
     * re-emitting) every second rebuilt the whole Home UI on a timer, which is
     * the jank the event-driven UI was built to avoid.
     */
    fun confirm(transactionId: String, nowMs: Long = System.currentTimeMillis()) {
        val current = _lease.value ?: return
        if (current.transactionId != transactionId) return
        val promoted = if (current.state == LeaseState.Requested) {
            LeaseState.Active
        } else {
            current.state
        }
        val stateChanged = promoted != current.state
        val timestampStale = nowMs - current.lastConfirmedAt >= CONFIRM_WRITE_INTERVAL_MS
        if (!stateChanged && !timestampStale) return
        write(current.copy(state = promoted, lastConfirmedAt = nowMs))
    }

    fun markStopping() {
        val current = _lease.value?.takeIf { it.isOpen } ?: return
        write(current.copy(state = LeaseState.Stopping))
    }

    /** The session is over. Frees the charger for the next driver. */
    fun release() {
        val current = _lease.value ?: return
        write(current.copy(state = LeaseState.Released))
    }

    fun clear() {
        _lease.value = null
        prefs()?.edit()?.remove(KEY_LEASE)?.apply()
    }

    private fun write(lease: SessionLease) {
        _lease.value = lease
        prefs()?.edit()?.putString(KEY_LEASE, lease.toJson())?.apply()
    }
}
