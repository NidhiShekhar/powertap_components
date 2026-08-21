package com.drivool.iot.powertap.session

import com.drivool.iot.powertap.contract.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionPolicyTest {

    // --- Occupancy --------------------------------------------------------

    @Test
    fun anyLiveAttemptCountsAsOccupyingTheCharger() {
        // The charger stops advertising as soon as a phone connects, so a
        // half-open attempt hides it from everyone else just as much.
        for (state in listOf(
            ConnectionState.Connected,
            ConnectionState.Connecting,
            ConnectionState.Scanning,
        )) {
            assertTrue(state.name, BleConnectionPolicy.isLinkBusy(state))
        }
        for (state in listOf(ConnectionState.Disconnected, ConnectionState.Failed)) {
            assertFalse(state.name, BleConnectionPolicy.isLinkBusy(state))
        }
    }

    // --- Silent reconnect -------------------------------------------------

    @Test
    fun noSessionMeansNoSilentConnect() {
        // The whole point of the change: a nearby charger must not be claimed
        // just because the app opened.
        assertFalse(
            BleConnectionPolicy.shouldResumeLink(
                hasOpenLease = false,
                userRequestedDisconnect = false,
                state = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun ownedSessionIsResumedSoItCanStillBeStopped() {
        assertTrue(
            BleConnectionPolicy.shouldResumeLink(
                hasOpenLease = true,
                userRequestedDisconnect = false,
                state = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun explicitDisconnectIsNotUndoneByResume() {
        assertFalse(
            BleConnectionPolicy.shouldResumeLink(
                hasOpenLease = true,
                userRequestedDisconnect = true,
                state = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun resumeDoesNotStackOnAnAttemptAlreadyRunning() {
        assertFalse(
            BleConnectionPolicy.shouldResumeLink(
                hasOpenLease = true,
                userRequestedDisconnect = false,
                state = ConnectionState.Connecting,
            ),
        )
    }

    // --- Retry after a drop ----------------------------------------------

    @Test
    fun dropDuringOwnedSessionIsRetried() {
        for (state in listOf(ConnectionState.Disconnected, ConnectionState.Failed)) {
            assertTrue(
                state.name,
                BleConnectionPolicy.shouldReconnectAfterDrop(
                    hasOpenLease = true,
                    userRequestedDisconnect = false,
                    state = state,
                ),
            )
        }
    }

    @Test
    fun dropWithoutSessionIsFinal() {
        assertFalse(
            BleConnectionPolicy.shouldReconnectAfterDrop(
                hasOpenLease = false,
                userRequestedDisconnect = false,
                state = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun userDisconnectIsNotTreatedAsADrop() {
        assertFalse(
            BleConnectionPolicy.shouldReconnectAfterDrop(
                hasOpenLease = true,
                userRequestedDisconnect = true,
                state = ConnectionState.Disconnected,
            ),
        )
    }

    // --- Handing the charger back ----------------------------------------

    @Test
    fun linkIsReleasedOnceTheSessionEnds() {
        assertTrue(
            BleConnectionPolicy.shouldReleaseAfterSession(
                hasOpenLease = false,
                state = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun linkIsHeldWhileASessionIsLive() {
        assertFalse(
            BleConnectionPolicy.shouldReleaseAfterSession(
                hasOpenLease = true,
                state = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun idleLinkIsReleasedAfterTheGraceWindow() {
        val now = 1_000_000L
        assertTrue(
            BleConnectionPolicy.shouldReleaseIdleLink(
                state = ConnectionState.Connected,
                hasOpenLease = false,
                lastUserActionAtMs = now - BleConnectionPolicy.IDLE_RELEASE_MS,
                nowMs = now,
            ),
        )
    }

    @Test
    fun idleLinkIsKeptWhileTheUserIsStillAround() {
        val now = 1_000_000L
        assertFalse(
            BleConnectionPolicy.shouldReleaseIdleLink(
                state = ConnectionState.Connected,
                hasOpenLease = false,
                lastUserActionAtMs = now - 1_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun idleReleaseNeverInterruptsCharging() {
        val now = 1_000_000L
        assertFalse(
            BleConnectionPolicy.shouldReleaseIdleLink(
                state = ConnectionState.Connected,
                hasOpenLease = true,
                lastUserActionAtMs = now - (BleConnectionPolicy.IDLE_RELEASE_MS * 10),
                nowMs = now,
            ),
        )
    }

    @Test
    fun idleReleaseWaitsForAFirstUserAction() {
        // No recorded action means the link was not opened by a tap we can time,
        // so there is no window to measure yet.
        assertFalse(
            BleConnectionPolicy.shouldReleaseIdleLink(
                state = ConnectionState.Connected,
                hasOpenLease = false,
                lastUserActionAtMs = 0L,
                nowMs = 1_000_000L,
            ),
        )
    }

    // --- Gating Start -----------------------------------------------------

    @Test
    fun startRequiresAFullyConnectedLink() {
        assertFalse(BleConnectionPolicy.needsConnectBeforeCharging(ConnectionState.Connected))
        for (state in listOf(
            ConnectionState.Disconnected,
            ConnectionState.Scanning,
            ConnectionState.Connecting,
            ConnectionState.Failed,
        )) {
            assertTrue(
                state.name,
                BleConnectionPolicy.needsConnectBeforeCharging(state),
            )
        }
    }
}
