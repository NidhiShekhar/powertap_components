package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.session.LeaseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSessionLogicTest {

    @Test
    fun bleReady_onlyWhenGattConnected() {
        assertTrue(ChargeSessionLogic.isBleReady(ConnectionState.Connected))
        assertFalse(ChargeSessionLogic.isBleReady(ConnectionState.Connecting))
        assertFalse(ChargeSessionLogic.isBleReady(ConnectionState.Scanning))
        assertFalse(ChargeSessionLogic.isBleReady(ConnectionState.Disconnected))
    }

    @Test
    fun skipDuplicateConnect_sameMacWhileAlive() {
        assertTrue(
            ChargeSessionLogic.shouldSkipDuplicateConnect(
                ConnectionState.Connected, "AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff",
            ),
        )
        assertTrue(
            ChargeSessionLogic.shouldSkipDuplicateConnect(
                ConnectionState.Connecting, "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldSkipDuplicateConnect(
                ConnectionState.Disconnected, "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldSkipDuplicateConnect(
                ConnectionState.Connected, "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66",
            ),
        )
    }

    @Test
    fun userTapRestartsAHalfOpenAttemptButNotALiveLink() {
        // The session-resume retry parks the transport in Scanning/Connecting
        // for most of its cycle, so that is where a tap normally lands. Treating
        // it as "already connecting" made the Connect button do nothing.
        assertTrue(ChargeSessionLogic.shouldRestartAttemptForUser(ConnectionState.Scanning))
        assertTrue(ChargeSessionLogic.shouldRestartAttemptForUser(ConnectionState.Connecting))
        assertFalse(ChargeSessionLogic.shouldRestartAttemptForUser(ConnectionState.Connected))
        assertFalse(ChargeSessionLogic.shouldRestartAttemptForUser(ConnectionState.Disconnected))
        assertFalse(ChargeSessionLogic.shouldRestartAttemptForUser(ConnectionState.Failed))
    }

    @Test
    fun leaseKeepsTheIdTheChargerWasGiven() {
        // The charger only ever knows the tid we put in RemoteStart, so a cloud
        // ack naming a different one cannot be honoured: adopting it builds a
        // lease no charger will confirm, which reads as someone else's session
        // and gets dropped — taking Stop and auto-reconnect with it.
        assertEquals(
            "T100",
            ChargeSessionLogic.leaseTransactionIdAfterAck(
                proposedTid = "T100",
                serverTid = "42",
                hardwareTid = "T100",
            ),
        )
        assertEquals(
            "T100",
            ChargeSessionLogic.leaseTransactionIdAfterAck(
                proposedTid = "T100",
                serverTid = "42",
                hardwareTid = null,
            ),
        )
    }

    @Test
    fun leaseFollowsTheServerOnlyWhenTheChargerIsRunningThatId() {
        assertEquals(
            "42",
            ChargeSessionLogic.leaseTransactionIdAfterAck(
                proposedTid = "T100",
                serverTid = "42",
                hardwareTid = "42",
            ),
        )
    }

    @Test
    fun leaseIsUnchangedByAnEmptyOrMatchingAck() {
        assertEquals(
            "T100",
            ChargeSessionLogic.leaseTransactionIdAfterAck("T100", null, "T100"),
        )
        assertEquals(
            "T100",
            ChargeSessionLogic.leaseTransactionIdAfterAck("T100", "  ", null),
        )
        assertEquals(
            "T100",
            ChargeSessionLogic.leaseTransactionIdAfterAck("T100", "T100", "T100"),
        )
    }

    @Test
    fun scanCache_staleAfterDisconnectWindow() {
        assertTrue(ChargeSessionLogic.isScanResultFresh(1_000L, 3_000L))
        assertFalse(ChargeSessionLogic.isScanResultFresh(1_000L, 1_000L + ChargeSessionLogic.SCAN_CACHE_FRESH_MS))
        assertFalse(ChargeSessionLogic.isScanResultFresh(9_000L, 1_000L))
    }

    @Test
    fun acceptServerState_ignoresStaleReplicaDuringInFlightCommand() {
        assertFalse(
            ChargeSessionLogic.shouldAcceptServerState(
                DeviceState.STATE_STARTING, DeviceState.STATE_AVAILABLE, commandInFlight = true,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldAcceptServerState(
                DeviceState.STATE_STOPPING, DeviceState.STATE_CHARGING, commandInFlight = true,
            ),
        )
        assertTrue(
            ChargeSessionLogic.shouldAcceptServerState(
                DeviceState.STATE_STOPPING, DeviceState.STATE_AVAILABLE, commandInFlight = true,
            ),
        )
        assertTrue(
            ChargeSessionLogic.shouldAcceptServerState(
                DeviceState.STATE_STOPPING, DeviceState.STATE_CHARGING, commandInFlight = false,
            ),
        )
    }

    @Test
    fun timeout_revertsStartingAndStopping() {
        assertTrue(ChargeSessionLogic.isCommandTimedOut(1_000L, 1_000L + 15_001L))
        assertFalse(ChargeSessionLogic.isCommandTimedOut(0L, 20_000L))
        assertEquals(
            DeviceState.STATE_AVAILABLE,
            ChargeSessionLogic.stateAfterCommandTimeout(DeviceState.STATE_STARTING),
        )
        assertEquals(
            DeviceState.STATE_CHARGING,
            ChargeSessionLogic.stateAfterCommandTimeout(DeviceState.STATE_STOPPING),
        )
    }

    @Test
    fun staleCloudCharging_isIgnoredWhenDeviceLooksDead() {
        assertEquals(
            DeviceState.STATE_AVAILABLE,
            ChargeSessionLogic.effectiveServerState(
                DeviceState.STATE_CHARGING,
                heartbeatFresh = false,
                bleReady = false,
                commandInFlight = false,
            ),
        )
        assertEquals(
            DeviceState.STATE_CHARGING,
            ChargeSessionLogic.effectiveServerState(
                DeviceState.STATE_CHARGING,
                heartbeatFresh = true,
                bleReady = false,
                commandInFlight = false,
            ),
        )
        assertEquals(
            DeviceState.STATE_CHARGING,
            ChargeSessionLogic.effectiveServerState(
                DeviceState.STATE_CHARGING,
                heartbeatFresh = false,
                bleReady = true,
                commandInFlight = false,
            ),
        )
        // Owning a session outranks a dead heartbeat: an unreachable charger is
        // unknown, not available, so Stop must stay reachable.
        assertEquals(
            DeviceState.STATE_CHARGING,
            ChargeSessionLogic.effectiveServerState(
                DeviceState.STATE_CHARGING,
                heartbeatFresh = false,
                bleReady = false,
                commandInFlight = false,
                hasOpenLease = true,
            ),
        )
        assertFalse(
            ChargeSessionLogic.isHeartbeatFresh(1_000L, 1_000L + ChargeSessionLogic.HEARTBEAT_STALE_MS),
        )
        assertTrue(ChargeSessionLogic.isHeartbeatFresh(1_000L, 20_000L))
    }

    @Test
    fun staleCloudStopping_doesNotLockSlider() {
        assertFalse(
            ChargeSessionLogic.sliderLocked(DeviceState.STATE_STOPPING, commandInFlight = false),
        )
        assertTrue(
            ChargeSessionLogic.sliderLocked(DeviceState.STATE_STOPPING, commandInFlight = true),
        )
        assertTrue(
            ChargeSessionLogic.treatAsCharging(DeviceState.STATE_STOPPING, commandInFlight = false),
        )
        assertFalse(
            ChargeSessionLogic.treatAsCharging(DeviceState.STATE_STOPPING, commandInFlight = true),
        )
        assertTrue(
            ChargeSessionLogic.treatAsCharging(DeviceState.STATE_STARTING, commandInFlight = false),
        )
        assertFalse(
            ChargeSessionLogic.treatAsCharging(DeviceState.STATE_STARTING, commandInFlight = true),
        )
    }

    @Test
    fun reconnectBackoff_capsAtFifteenSeconds() {
        assertEquals(1_000L, ChargeSessionLogic.reconnectDelayMs(0))
        assertEquals(2_000L, ChargeSessionLogic.reconnectDelayMs(1))
        assertEquals(16_000L.coerceAtMost(ChargeSessionLogic.MAX_RECONNECT_DELAY_MS), ChargeSessionLogic.reconnectDelayMs(4))
        assertEquals(ChargeSessionLogic.MAX_RECONNECT_DELAY_MS, ChargeSessionLogic.reconnectDelayMs(99))
    }

    @Test
    fun mqttForward_commandsAndCallResults_notMeterEcho() {
        assertTrue(
            ChargeSessionLogic.mqttShouldForwardToBle(
                """[2,"1","RemoteStop",{"tid":"T1"}]""",
            ),
        )
        assertTrue(
            ChargeSessionLogic.mqttShouldForwardToBle(
                """[2,"1","RemoteStart",{"tid":"T1","mode":"full"}]""",
            ),
        )
        assertTrue(
            ChargeSessionLogic.mqttShouldForwardToBle(
                """[3,"1048577",{"status":"Accepted","interval":10}]""",
            ),
        )
        assertFalse(
            ChargeSessionLogic.mqttShouldForwardToBle(
                """[2,"1","MeterValues",{"transactionId":"T1"}]""",
            ),
        )
        assertFalse(
            ChargeSessionLogic.mqttShouldForwardToBle(
                """[2,"1","BootNotification",{"reason":"PowerOn"}]""",
            ),
        )
    }

    @Test
    fun callResult_onlyMatchesTheCommandWeSent() {
        assertTrue(ChargeSessionLogic.callResultMatchesCommand("1787", "1787"))
        assertFalse(ChargeSessionLogic.callResultMatchesCommand("1048577", "1787"))
        assertFalse(ChargeSessionLogic.callResultMatchesCommand("1048577", null))
    }

    @Test
    fun serverAck_extractsTransactionId() {
        val result = mapOf(
            "resp" to mapOf("transactionId" to "T1787202232462"),
            "cmd" to mapOf("api" to "RemoteStart"),
        )
        assertEquals("T1787202232462", ChargeSessionLogic.transactionIdFromServerAck(result))
    }

    @Test
    fun duplicateMqttForward_dropsSecondCopyWithinTwoSeconds() {
        val ack = """[3,"1048581",{"currentTime":"2026-08-20T05:20:36.536Z"}]"""
        assertTrue(
            ChargeSessionLogic.isDuplicateMqttForward(ack, ack, 1_000L, 1_060L),
        )
        assertFalse(
            ChargeSessionLogic.isDuplicateMqttForward(ack, ack, 1_000L, 4_000L),
        )
        assertFalse(
            ChargeSessionLogic.isDuplicateMqttForward(ack, "[3,\"1\"]", 1_000L, 1_060L),
        )
    }

    @Test
    fun sliderState_offersStopOnlyWhenThereIsASessionToStop() {
        // Cloud state alone: the id Stop would target is gone, so the control
        // could only report "no session to stop".
        assertEquals(
            DeviceState.STATE_AVAILABLE,
            ChargeSessionLogic.sliderState(
                localState = DeviceState.STATE_CHARGING,
                hasOpenLease = false,
                hardwareCharging = false,
                commandInFlight = false,
            ),
        )
        assertEquals(
            DeviceState.STATE_CHARGING,
            ChargeSessionLogic.sliderState(
                localState = DeviceState.STATE_CHARGING,
                hasOpenLease = true,
                hardwareCharging = false,
                commandInFlight = false,
            ),
        )
        // Seeing the charger busy without owning the lease must not unlock Stop
        // for a stranger (that was the take-control hole).
        assertEquals(
            DeviceState.STATE_AVAILABLE,
            ChargeSessionLogic.sliderState(
                localState = DeviceState.STATE_CHARGING,
                hasOpenLease = false,
                hardwareCharging = true,
                commandInFlight = false,
            ),
        )
    }

    @Test
    fun ownsRunningSession_requiresMatchingTransactionIds() {
        assertTrue(ChargeSessionLogic.ownsRunningSession("T1", "T1"))
        assertTrue(ChargeSessionLogic.ownsRunningSession(" T1 ", "T1"))
        assertFalse(ChargeSessionLogic.ownsRunningSession("T1", "T2"))
        assertFalse(ChargeSessionLogic.ownsRunningSession(null, "T1"))
        assertFalse(ChargeSessionLogic.ownsRunningSession("T1", null))
        assertFalse(ChargeSessionLogic.ownsRunningSession("", "T1"))
    }

    @Test
    fun accountOwnsLiveSession_ignoresHeartbeatAndTidRename() {
        // Walk-away: heartbeat is stale, charger may have a different tid than
        // the one we proposed. Ownership of the charge must still hold.
        assertTrue(
            ChargeSessionLogic.accountOwnsLiveSession(
                currentAccount = "alice",
                ownerAccount = "alice",
                cloudState = DeviceState.STATE_CHARGING,
            ),
        )
        assertFalse(
            ChargeSessionLogic.accountOwnsLiveSession(
                currentAccount = "alice",
                ownerAccount = "bob",
                cloudState = DeviceState.STATE_CHARGING,
            ),
        )
        assertFalse(
            ChargeSessionLogic.accountOwnsLiveSession(
                currentAccount = "guest",
                ownerAccount = "guest",
                cloudState = DeviceState.STATE_CHARGING,
            ),
        )
    }

    @Test
    fun idleEvidenceIsHeldOnlyForConfirmedSessions() {
        val grace = 20_000L
        // Active lease, BLE down: hold MQTT idle so a walk-away does not end the charge.
        assertTrue(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Active,
                bleReady = false,
                isStopTransaction = false,
                msSinceReconnect = 60_000L,
                reconnectGraceMs = grace,
            ),
        )
        // Active lease, just reconnected: hold through grace.
        assertTrue(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Active,
                bleReady = true,
                isStopTransaction = false,
                msSinceReconnect = 1_000L,
                reconnectGraceMs = grace,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Active,
                bleReady = true,
                isStopTransaction = false,
                msSinceReconnect = grace,
                reconnectGraceMs = grace,
            ),
        )
        // Requested never started: first idle must end it, not hold it.
        assertFalse(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Requested,
                bleReady = false,
                isStopTransaction = false,
                msSinceReconnect = 0L,
                reconnectGraceMs = grace,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Requested,
                bleReady = true,
                isStopTransaction = false,
                msSinceReconnect = 1_000L,
                reconnectGraceMs = grace,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = LeaseState.Active,
                bleReady = false,
                isStopTransaction = true,
                msSinceReconnect = 0L,
                reconnectGraceMs = grace,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = null,
                bleReady = false,
                isStopTransaction = false,
                msSinceReconnect = 0L,
                reconnectGraceMs = grace,
            ),
        )
    }

    @Test
    fun liveOwnedSession_requiresMoreThanAnOpenLease() {
        // Failed Start left a Requested lease while UI is Available — not mid-charge.
        assertFalse(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Requested,
                hardwareCharging = false,
                localChargingUi = false,
            ),
        )
        assertFalse(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Active,
                hardwareCharging = false,
                localChargingUi = false,
            ),
        )
        assertTrue(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Active,
                hardwareCharging = false,
                localChargingUi = true,
            ),
        )
        assertTrue(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Requested,
                hardwareCharging = false,
                localChargingUi = true,
            ),
        )
        assertTrue(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Requested,
                hardwareCharging = true,
                localChargingUi = false,
            ),
        )
        assertTrue(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = LeaseState.Stopping,
                hardwareCharging = false,
                localChargingUi = false,
            ),
        )
        assertFalse(
            ChargeSessionLogic.isLiveOwnedSession(
                leaseState = null,
                hardwareCharging = true,
                localChargingUi = true,
            ),
        )
    }

    @Test
    fun reconnectAttemptRestartsWhenStuckScanningOrConnecting() {
        val started = 1_000L
        val stale = ChargeSessionLogic.RECONNECT_ATTEMPT_STALE_MS
        assertTrue(
            ChargeSessionLogic.reconnectAttemptNeedsRestart(
                ConnectionState.Scanning, started, started + stale,
            ),
        )
        assertTrue(
            ChargeSessionLogic.reconnectAttemptNeedsRestart(
                ConnectionState.Connecting, started, started + stale,
            ),
        )
        assertFalse(
            ChargeSessionLogic.reconnectAttemptNeedsRestart(
                ConnectionState.Scanning, started, started + stale - 1,
            ),
        )
        assertFalse(
            ChargeSessionLogic.reconnectAttemptNeedsRestart(
                ConnectionState.Connected, started, started + stale,
            ),
        )
        assertFalse(
            ChargeSessionLogic.reconnectAttemptNeedsRestart(
                ConnectionState.Scanning, 0L, started + stale,
            ),
        )
    }

    @Test
    fun silentGattDuringOwnedSessionIsDropped() {
        val last = 1_000L
        assertTrue(
            ChargeSessionLogic.shouldDropSilentGatt(
                bleReady = true,
                hasOpenLease = true,
                lastBlePacketAtMs = last,
                nowMs = last + ChargeSessionLogic.HEARTBEAT_STALE_MS,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldDropSilentGatt(
                bleReady = true,
                hasOpenLease = true,
                lastBlePacketAtMs = last,
                nowMs = last + 1_000L,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldDropSilentGatt(
                bleReady = true,
                hasOpenLease = false,
                lastBlePacketAtMs = last,
                nowMs = last + ChargeSessionLogic.HEARTBEAT_STALE_MS,
            ),
        )
        assertFalse(
            ChargeSessionLogic.shouldDropSilentGatt(
                bleReady = false,
                hasOpenLease = true,
                lastBlePacketAtMs = last,
                nowMs = last + ChargeSessionLogic.HEARTBEAT_STALE_MS,
            ),
        )
    }

    @Test
    fun stopTargetsTheLiveHardwareId() {
        assertEquals("T2", ChargeSessionLogic.stopTargetTransactionId("T1", "T2"))
        assertEquals("T1", ChargeSessionLogic.stopTargetTransactionId("T1", null))
        assertEquals("T1", ChargeSessionLogic.stopTargetTransactionId("T1", "  "))
        assertEquals("T1", ChargeSessionLogic.stopTargetTransactionId("T1", "T1"))
    }

    @Test
    fun accountOwnsCloudSession_requiresMatchingLoginAndLiveTid() {
        assertTrue(
            ChargeSessionLogic.accountOwnsCloudSession(
                currentAccount = "alice",
                ownerAccount = "alice",
                transactionId = "T1",
                cloudState = DeviceState.STATE_CHARGING,
                heartbeatFresh = true,
            ),
        )
        assertFalse(
            ChargeSessionLogic.accountOwnsCloudSession(
                currentAccount = "alice",
                ownerAccount = "bob",
                transactionId = "T1",
                cloudState = DeviceState.STATE_CHARGING,
                heartbeatFresh = true,
            ),
        )
        assertFalse(
            ChargeSessionLogic.accountOwnsCloudSession(
                currentAccount = "guest",
                ownerAccount = "guest",
                transactionId = "T1",
                cloudState = DeviceState.STATE_CHARGING,
                heartbeatFresh = true,
            ),
        )
        assertFalse(
            ChargeSessionLogic.accountOwnsCloudSession(
                currentAccount = "alice",
                ownerAccount = "alice",
                transactionId = "T1",
                cloudState = DeviceState.STATE_CHARGING,
                heartbeatFresh = false,
                requireFreshHeartbeat = true,
            ),
        )
    }

    @Test
    fun sliderState_keepsOurOwnCommandVisibleWhileItIsInFlight() {
        // Start claims the lease before sending, but Stop and the confirmation
        // window must not flicker back to "Slide to Start" either.
        assertEquals(
            DeviceState.STATE_STOPPING,
            ChargeSessionLogic.sliderState(
                localState = DeviceState.STATE_STOPPING,
                hasOpenLease = false,
                hardwareCharging = false,
                commandInFlight = true,
            ),
        )
    }

    @Test
    fun sliderState_leavesNonChargingStatesAlone() {
        assertEquals(
            DeviceState.STATE_AVAILABLE,
            ChargeSessionLogic.sliderState(
                localState = DeviceState.STATE_AVAILABLE,
                hasOpenLease = false,
                hardwareCharging = false,
                commandInFlight = false,
            ),
        )
    }

    @Test
    fun yAxisWindow_expandsTinyPowerRangeSoLabelsDiffer() {
        val window = ChargeSessionLogic.yAxisWindow(listOf(1.009f, 1.007f, 1.006f))
        assertTrue(window.max - window.min > 0.05f)
        assertEquals(2, window.decimals)
        val low = String.format(java.util.Locale.US, "%.${window.decimals}f", window.min)
        val high = String.format(java.util.Locale.US, "%.${window.decimals}f", window.max)
        assertTrue(low != high)
    }
}
