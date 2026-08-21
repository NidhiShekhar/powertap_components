package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.ConnectionState
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
        assertFalse(ChargeSessionLogic.ownsRunningSession("T1", "T2"))
        assertFalse(ChargeSessionLogic.ownsRunningSession(null, "T1"))
        assertFalse(ChargeSessionLogic.ownsRunningSession("T1", null))
        assertFalse(ChargeSessionLogic.ownsRunningSession("", "T1"))
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
