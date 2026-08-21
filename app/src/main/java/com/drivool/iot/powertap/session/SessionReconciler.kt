package com.drivool.iot.powertap.session

import com.drivool.iot.powertap.DeviceState

/**
 * The cloud's copy of session state.
 *
 * [ownerAccount] is the login that started the charge (same account key as
 * Commands/Response). It is what lets a user reclaim Stop after clearing app
 * data — strangers still cannot.
 */
data class CloudSession(
    val transactionId: String?,
    val state: Int,
    val heartbeatFresh: Boolean,
    val ownerAccount: String? = null,
)

/** What the app should do after comparing its lease against the charger. */
sealed interface Reconciliation {
    /** Our lease matches what the charger is running. Keep going. */
    data class Confirmed(val transactionId: String) : Reconciliation

    /**
     * This login owns the live session in the cloud, but this phone has no local
     * lease (reinstall / cleared data). Restore the lease and resume control.
     */
    data class Reclaim(val transactionId: String) : Reconciliation

    /**
     * The charger is running a session this account does not own.
     *
     * Only the account that started [transactionId] may stop it.
     * [ourStaleTransactionId] is set when we held a different id; that claim is
     * dead and should be dropped locally without touching the charger.
     */
    data class Occupied(
        val transactionId: String,
        val ourStaleTransactionId: String? = null,
    ) : Reconciliation

    /** The charger positively reported idle. Close the session and release. */
    data class Ended(val transactionId: String) : Reconciliation

    /** Held far too long with no confirmation. Let the user clear it. */
    data class Expired(val transactionId: String) : Reconciliation

    /**
     * No usable evidence. Hold the lease and keep trying to reach the charger.
     *
     * This is the case the old code got wrong: it treated "no heartbeat and no
     * BLE" as proof the session had ended, cleared the transaction id and wiped
     * the cloud node — while the charger carried on charging, leaving the
     * session dangling and unstoppable.
     */
    data object HoldUnknown : Reconciliation

    /** Nothing running, nothing owed. */
    data object Idle : Reconciliation
}

/**
 * Decides whether *this login* still owns the live charging session.
 *
 * Precedence is strict: **hardware > account ownership > local lease > cloud**.
 *
 * The durable proof of ownership is the Firebase account that started the
 * charge. The phone-local lease is a cache for BLE resume; if it is lost,
 * matching [CloudSession.ownerAccount] to the signed-in account restores it.
 * A different account never gets control.
 */
object SessionReconciler {

    /** A lease this old with no confirmation is presumed abandoned. */
    const val MAX_LEASE_AGE_MS = 12L * 60L * 60L * 1000L

    /**
     * How long after Start an idle report is ignored.
     *
     * The firmware heartbeats every ~10s and the relay takes a moment to close,
     * so an idle packet just after Start is expected rather than a failure.
     * Firebase often marks the lease Active (billed) *before* StartTransaction
     * arrives — grace must survive that promotion, or the next heartbeat ends a
     * session that has only just begun.
     */
    const val START_GRACE_MS = 20_000L

    fun reconcile(
        lease: SessionLease?,
        hardware: HardwareSession,
        cloud: CloudSession,
        nowMs: Long,
        currentAccount: String? = null,
    ): Reconciliation {
        val open = lease?.takeIf { it.isOpen }
            ?: return withoutLease(hardware, cloud, currentAccount)

        // Evidence that predates our claim says nothing about it.
        if (hardware != HardwareSession.Unknown &&
            hardware.observedAtOrZero < open.startedAt
        ) {
            return Reconciliation.HoldUnknown
        }

        return when (hardware) {
            is HardwareSession.Charging -> confirmOrOccupied(open, hardware.transactionId)

            is HardwareSession.Idle ->
                if (isWithinStartGrace(open, nowMs)) {
                    // Relay has not closed yet; not a failure.
                    Reconciliation.HoldUnknown
                } else {
                    Reconciliation.Ended(open.transactionId)
                }

            HardwareSession.Unknown -> when {
                cloudConfirms(open, cloud) -> Reconciliation.Confirmed(open.transactionId)
                open.ageMs(nowMs) > MAX_LEASE_AGE_MS -> Reconciliation.Expired(open.transactionId)
                else -> Reconciliation.HoldUnknown
            }
        }
    }

    private fun withoutLease(
        hardware: HardwareSession,
        cloud: CloudSession,
        currentAccount: String?,
    ): Reconciliation = when (hardware) {
        is HardwareSession.Charging -> {
            val liveTid = hardware.transactionId
            if (liveTid.isBlank()) {
                // Charging, identity unknown on the wire — reclaim only if cloud
                // already names a tid this account owns.
                reclaimFromCloud(cloud, currentAccount, requireFreshHeartbeat = false)
                    ?: Reconciliation.HoldUnknown
            } else if (canReclaim(liveTid, cloud, currentAccount, requireFreshHeartbeat = false)) {
                Reconciliation.Reclaim(liveTid)
            } else {
                Reconciliation.Occupied(liveTid)
            }
        }

        is HardwareSession.Idle -> Reconciliation.Idle

        HardwareSession.Unknown ->
            // No charger packets: only reclaim when cloud ownership is fresh,
            // so a stale monitor node cannot resurrect a finished session.
            reclaimFromCloud(cloud, currentAccount, requireFreshHeartbeat = true)
                ?: Reconciliation.HoldUnknown
    }

    private fun confirmOrOccupied(lease: SessionLease, hardwareTid: String): Reconciliation = when {
        // Older firmware reports no id. The relay is on and we hold a lease, so
        // the session is almost certainly ours — keep it rather than orphan it.
        hardwareTid.isBlank() -> Reconciliation.Confirmed(lease.transactionId)
        hardwareTid == lease.transactionId -> Reconciliation.Confirmed(hardwareTid)
        // Charger runs a different session. Ours is stale; do not take theirs over.
        else -> Reconciliation.Occupied(hardwareTid, ourStaleTransactionId = lease.transactionId)
    }

    private fun reclaimFromCloud(
        cloud: CloudSession,
        currentAccount: String?,
        requireFreshHeartbeat: Boolean,
    ): Reconciliation.Reclaim? {
        val tid = cloud.transactionId?.takeIf { it.isNotBlank() } ?: return null
        if (!canReclaim(tid, cloud, currentAccount, requireFreshHeartbeat)) return null
        return Reconciliation.Reclaim(tid)
    }

    private fun canReclaim(
        transactionId: String,
        cloud: CloudSession,
        currentAccount: String?,
        requireFreshHeartbeat: Boolean,
    ): Boolean {
        if (currentAccount.isNullOrBlank() || currentAccount == "guest") return false
        if (cloud.ownerAccount.isNullOrBlank()) return false
        if (cloud.ownerAccount != currentAccount) return false
        val cloudTid = cloud.transactionId?.takeIf { it.isNotBlank() } ?: return false
        // Cloud must name the same session we are about to reclaim.
        if (cloudTid != transactionId) return false
        if (!isCloudCharging(cloud.state)) return false
        if (requireFreshHeartbeat && !cloud.heartbeatFresh) return false
        return true
    }

    private fun isWithinStartGrace(lease: SessionLease, nowMs: Long): Boolean {
        // User-initiated stop must honour idle immediately, even mid-grace.
        if (lease.state == LeaseState.Stopping) return false
        if (!lease.isOpen) return false
        return lease.ageMs(nowMs) < START_GRACE_MS
    }

    private fun cloudConfirms(lease: SessionLease, cloud: CloudSession): Boolean =
        cloud.heartbeatFresh &&
            isCloudCharging(cloud.state) &&
            cloud.transactionId == lease.transactionId

    private fun isCloudCharging(state: Int): Boolean =
        state == DeviceState.STATE_CHARGING ||
            state == DeviceState.STATE_STARTED ||
            state == DeviceState.STATE_STARTING
}
