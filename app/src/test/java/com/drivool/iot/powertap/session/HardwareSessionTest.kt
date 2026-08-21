package com.drivool.iot.powertap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the packet-type to relay-state mapping. The JSON wrapper around it is
 * exercised on-device; this is the decision table that the whole session model
 * rests on.
 */
class HardwareSessionTest {

    private val at = 5_000L

    private fun action(
        action: String,
        transactionId: String = "",
        status: String = "",
    ) = HardwareSession.fromAction(action, transactionId, status, at)

    @Test
    fun meterValuesMeansChargingAndCarriesTheLiveId() {
        // MeterValues is only emitted while the relay is closed, so it is the
        // one packet that proves both "charging" and "which session".
        assertEquals(
            HardwareSession.Charging("T7", at),
            action("MeterValues", transactionId = "T7"),
        )
    }

    @Test
    fun startTransactionMeansCharging() {
        assertEquals(
            HardwareSession.Charging("T7", at),
            action("StartTransaction", transactionId = "T7"),
        )
    }

    @Test
    fun meterValuesWithoutAnIdStillProvesCharging() {
        assertEquals(
            HardwareSession.Charging("", at),
            action("MeterValues"),
        )
    }

    @Test
    fun heartbeatIsProofOfIdle() {
        assertEquals(HardwareSession.Idle(at), action("Heartbeat"))
        assertEquals(HardwareSession.Idle(at), action("HeartBeat"))
    }

    @Test
    fun heartbeatTransactionIdIsNeverTreatedAsLive() {
        // A heartbeat only goes out while the relay is open, so any id on it
        // belongs to a finished session. Reading it as live is exactly how a
        // stale session gets adopted.
        assertEquals(
            HardwareSession.Idle(at),
            action("Heartbeat", transactionId = "T7"),
        )
    }

    @Test
    fun stopTransactionMeansIdle() {
        assertEquals(HardwareSession.Idle(at), action("StopTransaction"))
    }

    @Test
    fun statusNotificationReportsIdleOnlyWhenFinished() {
        assertEquals(
            HardwareSession.Idle(at),
            action("StatusNotification", status = "Available"),
        )
        assertEquals(
            HardwareSession.Idle(at),
            action("StatusNotification", status = "Finishing"),
        )
        assertEquals(
            HardwareSession.Idle(at),
            action("StatusNotification", status = "available"),
        )
    }

    @Test
    fun statusNotificationSaysNothingWhileCharging() {
        // "Charging" here carries no transaction id, so it must not overwrite a
        // MeterValues reading that does.
        assertNull(action("StatusNotification", status = "Charging"))
        assertNull(action("StatusNotification", status = "Preparing"))
    }

    @Test
    fun unrelatedFramesSayNothing() {
        assertNull(action("BootNotification"))
        assertNull(action("Authorize"))
        assertNull(action(""))
    }

    @Test
    fun observedAtIsZeroOnlyWhenUnknown() {
        assertEquals(0L, HardwareSession.Unknown.observedAtOrZero)
        assertEquals(at, HardwareSession.Idle(at).observedAtOrZero)
        assertEquals(at, HardwareSession.Charging("T7", at).observedAtOrZero)
    }
}
