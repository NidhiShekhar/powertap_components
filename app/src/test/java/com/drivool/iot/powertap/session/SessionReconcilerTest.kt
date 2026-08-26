package com.drivool.iot.powertap.session

import com.drivool.iot.powertap.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReconcilerTest {

    private val leaseStart = 100_000L
    private val now = leaseStart + 60_000L

    private fun lease(
        tid: String = "T1",
        state: LeaseState = LeaseState.Active,
        startedAt: Long = leaseStart,
    ) = SessionLease(
        transactionId = tid,
        deviceId = "70041dafd038",
        bleAddress = "AA:BB:CC:DD:EE:FF",
        mode = "full",
        startedAt = startedAt,
        state = state,
        lastConfirmedAt = startedAt,
    )

    private fun cloud(
        tid: String? = null,
        state: Int = DeviceState.STATE_AVAILABLE,
        fresh: Boolean = false,
        owner: String? = null,
    ) = CloudSession(
        transactionId = tid,
        state = state,
        heartbeatFresh = fresh,
        ownerAccount = owner,
    )

    private fun reconcile(
        lease: SessionLease?,
        hardware: HardwareSession,
        cloud: CloudSession = cloud(),
        nowMs: Long = now,
        account: String? = "alice",
    ) = SessionReconciler.reconcile(lease, hardware, cloud, nowMs, account)

    // --- No session owned -------------------------------------------------

    @Test
    fun noLease_noEvidence_assertsNothing() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(null, HardwareSession.Unknown),
        )
    }

    @Test
    fun noLease_chargerIdle_isIdle() {
        assertEquals(
            Reconciliation.Idle,
            reconcile(null, HardwareSession.Idle(now)),
        )
    }

    @Test
    fun noLease_chargerRunningSession_isOccupiedForOtherAccount() {
        assertEquals(
            Reconciliation.Occupied("T9"),
            reconcile(
                null,
                HardwareSession.Charging("T9", now),
                cloud(tid = "T9", state = DeviceState.STATE_CHARGING, fresh = true, owner = "bob"),
                account = "alice",
            ),
        )
    }

    @Test
    fun noLease_chargerRunningSession_reclaimsWhenCloudSaysWeOwnIt() {
        // Reinstall / cleared data: same login, no local lease, cloud ownership intact.
        assertEquals(
            Reconciliation.Reclaim("T9"),
            reconcile(
                null,
                HardwareSession.Charging("T9", now),
                cloud(tid = "T9", state = DeviceState.STATE_CHARGING, fresh = true, owner = "alice"),
                account = "alice",
            ),
        )
    }

    @Test
    fun noLease_cloudOwnershipAlone_reclaimsWhenHeartbeatFresh() {
        assertEquals(
            Reconciliation.Reclaim("T9"),
            reconcile(
                null,
                HardwareSession.Unknown,
                cloud(tid = "T9", state = DeviceState.STATE_CHARGING, fresh = true, owner = "alice"),
                account = "alice",
            ),
        )
    }

    @Test
    fun noLease_staleCloudOwnership_doesNotReclaimWithoutHardware() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                null,
                HardwareSession.Unknown,
                cloud(tid = "T9", state = DeviceState.STATE_CHARGING, fresh = false, owner = "alice"),
                account = "alice",
            ),
        )
    }

    @Test
    fun noLease_guestAccount_neverReclaims() {
        assertEquals(
            Reconciliation.Occupied("T9"),
            reconcile(
                null,
                HardwareSession.Charging("T9", now),
                cloud(tid = "T9", state = DeviceState.STATE_CHARGING, fresh = true, owner = "guest"),
                account = "guest",
            ),
        )
    }

    @Test
    fun noLease_chargingWithoutId_doesNotClaimIt() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(null, HardwareSession.Charging("", now)),
        )
    }

    @Test
    fun releasedLease_isTreatedAsOwningNothing() {
        assertEquals(
            Reconciliation.Occupied("T9"),
            reconcile(
                lease(state = LeaseState.Released),
                HardwareSession.Charging("T9", now),
            ),
        )
    }

    // --- Session owned ---------------------------------------------------

    @Test
    fun ourSessionRunning_isConfirmed() {
        assertEquals(
            Reconciliation.Confirmed("T1"),
            reconcile(lease(), HardwareSession.Charging("T1", now)),
        )
    }

    @Test
    fun chargerRunningDifferentId_reclaimsWhenWeOwnTheCloudSession() {
        // Firmware overwrote strTID after our Start. Cloud still names us as
        // owner. Adopt the live id so Stop and auto-reconnect keep working.
        assertEquals(
            Reconciliation.Reclaim("T2"),
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Charging("T2", now),
                cloud(tid = "T1", state = DeviceState.STATE_CHARGING, fresh = true, owner = "alice"),
                account = "alice",
            ),
        )
    }

    @Test
    fun chargerRunningDifferentId_reclaimsWhenCloudTidAlsoMoved() {
        assertEquals(
            Reconciliation.Reclaim("T2"),
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Charging("T2", now),
                cloud(tid = "T2", state = DeviceState.STATE_CHARGING, fresh = true, owner = "alice"),
                account = "alice",
            ),
        )
    }

    @Test
    fun chargerRunningDifferentId_reclaimsActiveLeaseWithNoForeignOwner() {
        // Guest / cloud lag: we hold an Active lease, nobody else is named.
        // Dropping it is what stranded a walk-away.
        assertEquals(
            Reconciliation.Reclaim("T2"),
            reconcile(lease(tid = "T1"), HardwareSession.Charging("T2", now)),
        )
    }

    @Test
    fun chargerRunningDifferentId_isOccupiedWhenSomeoneElseOwnsIt() {
        assertEquals(
            Reconciliation.Occupied("T2", ourStaleTransactionId = "T1"),
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Charging("T2", now),
                cloud(tid = "T2", state = DeviceState.STATE_CHARGING, fresh = true, owner = "bob"),
                account = "alice",
            ),
        )
    }

    @Test
    fun requestedSessionDifferentIdDuringGrace_isAdoptedAsOurStart() {
        val startedAt = now - 1_000L
        assertEquals(
            Reconciliation.Reclaim("T2"),
            reconcile(
                lease(tid = "T1", state = LeaseState.Requested, startedAt = startedAt),
                HardwareSession.Charging("T2", now),
            ),
        )
    }

    @Test
    fun requestedSessionDifferentIdAfterGrace_isOccupiedWhenStartDidNotTake() {
        val startedAt = now - (SessionReconciler.START_GRACE_MS + 1_000L)
        assertEquals(
            Reconciliation.Occupied("T2", ourStaleTransactionId = "T1"),
            reconcile(
                lease(tid = "T1", state = LeaseState.Requested, startedAt = startedAt),
                HardwareSession.Charging("T2", now),
            ),
        )
    }

    @Test
    fun olderFirmwareWithoutIds_keepsOurSessionRatherThanOrphanIt() {
        assertEquals(
            Reconciliation.Confirmed("T1"),
            reconcile(lease(tid = "T1"), HardwareSession.Charging("", now)),
        )
    }

    @Test
    fun chargerReportsIdle_endsOurSession() {
        assertEquals(
            Reconciliation.Ended("T1"),
            reconcile(lease(), HardwareSession.Idle(now)),
        )
    }

    @Test
    fun unreachableCharger_holdsSessionInsteadOfClearingIt() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(lease(), HardwareSession.Unknown),
        )
    }

    // --- Cloud is corroboration only -------------------------------------

    @Test
    fun freshCloudCharging_confirmsWhenIdMatches() {
        assertEquals(
            Reconciliation.Confirmed("T1"),
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Unknown,
                cloud(tid = "T1", state = DeviceState.STATE_CHARGING, fresh = true),
            ),
        )
    }

    @Test
    fun freshCloudCharging_ignoredWhenIdDiffers() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Unknown,
                cloud(tid = "T2", state = DeviceState.STATE_CHARGING, fresh = true),
            ),
        )
    }

    @Test
    fun cloudCanNeverEndASession() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                lease(tid = "T1"),
                HardwareSession.Unknown,
                cloud(tid = "", state = DeviceState.STATE_AVAILABLE, fresh = true),
            ),
        )
    }

    // --- Timing guards ---------------------------------------------------

    @Test
    fun evidenceOlderThanOurClaim_isIgnored() {
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                lease(state = LeaseState.Requested, startedAt = leaseStart),
                HardwareSession.Idle(leaseStart - 1_000L),
            ),
        )
    }

    @Test
    fun freshlyRequestedSession_toleratesIdleDuringRelayGrace() {
        val startedAt = now - (SessionReconciler.START_GRACE_MS - 1_000L)
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                lease(state = LeaseState.Requested, startedAt = startedAt),
                HardwareSession.Idle(now),
            ),
        )
    }

    @Test
    fun firebasePromotedActive_stillToleratesIdleDuringStartGrace() {
        // Billing ack often arrives before StartTransaction. That must not remove
        // grace, or the next heartbeat ends a session that has only just begun.
        val startedAt = now - (SessionReconciler.START_GRACE_MS - 1_000L)
        assertEquals(
            Reconciliation.HoldUnknown,
            reconcile(
                lease(state = LeaseState.Active, startedAt = startedAt),
                HardwareSession.Idle(now),
            ),
        )
    }

    @Test
    fun requestedSessionStillIdleAfterGrace_isTreatedAsFailedStart() {
        val startedAt = now - (SessionReconciler.START_GRACE_MS + 1_000L)
        assertEquals(
            Reconciliation.Ended("T1"),
            reconcile(
                lease(state = LeaseState.Requested, startedAt = startedAt),
                HardwareSession.Idle(now),
            ),
        )
    }

    @Test
    fun activeSessionIdleAfterGrace_ends() {
        val startedAt = now - (SessionReconciler.START_GRACE_MS + 1_000L)
        assertEquals(
            Reconciliation.Ended("T1"),
            reconcile(
                lease(state = LeaseState.Active, startedAt = startedAt),
                HardwareSession.Idle(now),
            ),
        )
    }

    @Test
    fun stoppingIgnoresStartGraceSoIdleEndsImmediately() {
        val startedAt = now - 1_000L
        assertEquals(
            Reconciliation.Ended("T1"),
            reconcile(
                lease(state = LeaseState.Stopping, startedAt = startedAt),
                HardwareSession.Idle(now),
            ),
        )
    }

    @Test
    fun longUnconfirmedSession_expiresSoItCanBeCleared() {
        val startedAt = now - (SessionReconciler.MAX_LEASE_AGE_MS + 1_000L)
        assertEquals(
            Reconciliation.Expired("T1"),
            reconcile(
                lease(startedAt = startedAt),
                HardwareSession.Unknown,
            ),
        )
    }

    @Test
    fun longRunningSessionStillCharging_neverExpires() {
        val startedAt = now - (SessionReconciler.MAX_LEASE_AGE_MS + 1_000L)
        assertEquals(
            Reconciliation.Confirmed("T1"),
            reconcile(
                lease(startedAt = startedAt),
                HardwareSession.Charging("T1", now),
            ),
        )
    }
}
