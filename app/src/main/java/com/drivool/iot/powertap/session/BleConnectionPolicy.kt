package com.drivool.iot.powertap.session

import com.drivool.iot.powertap.contract.ConnectionState

/**
 * When the app is allowed to hold a BLE link to a charger.
 *
 * The charger stops advertising the moment a phone connects (see `onConnect` in
 * esp/aug2.ino), so a connected phone makes the PowerTap invisible to everyone
 * else. Holding a link is therefore a form of occupancy and needs justifying.
 *
 * This separates two things the old code treated as one:
 *
 *  - **Convenience reconnect** ("the app opened, so connect to the last
 *    charger") — removed. This is what silently locked chargers.
 *  - **Session resume** ("I have a running session and must not lose the
 *    ability to stop it") — kept, including the backoff retry after a drop.
 *
 * Everything else requires a deliberate tap.
 */
object BleConnectionPolicy {

    /**
     * How long a connected-but-idle link is kept before it is released.
     * Long enough to read the screen and start a charge, short enough that a
     * user who wanders off does not keep the charger to themselves.
     */
    const val IDLE_RELEASE_MS = 90_000L

    /** Connecting, scanning and connected all occupy the charger. */
    fun isLinkBusy(state: ConnectionState): Boolean =
        state == ConnectionState.Connected ||
            state == ConnectionState.Connecting ||
            state == ConnectionState.Scanning

    /**
     * Reconnect without the user asking, on app open or when Bluetooth returns.
     * Only when this phone holds an open lease for the session it started — the
     * transaction id on that lease is later matched against the charger before
     * Stop is offered.
     */
    fun shouldResumeLink(
        hasOpenLease: Boolean,
        userRequestedDisconnect: Boolean,
        state: ConnectionState,
    ): Boolean {
        if (!hasOpenLease) return false
        if (userRequestedDisconnect) return false
        return !isLinkBusy(state)
    }

    /**
     * Retry after the link dropped unexpectedly. Without an owned session a drop
     * is final: the user reconnects by tapping, which keeps "manual" honest.
     */
    fun shouldReconnectAfterDrop(
        hasOpenLease: Boolean,
        userRequestedDisconnect: Boolean,
        state: ConnectionState,
    ): Boolean {
        if (userRequestedDisconnect) return false
        if (state != ConnectionState.Disconnected && state != ConnectionState.Failed) return false
        return hasOpenLease
    }

    /**
     * Give the charger back after a session ends. Anything other than a live
     * session means we are occupying it for no reason.
     */
    fun shouldReleaseAfterSession(hasOpenLease: Boolean, state: ConnectionState): Boolean =
        !hasOpenLease && isLinkBusy(state)

    /**
     * Courtesy release: connected, no session, and the user has not touched
     * anything for [idleWindowMs].
     */
    fun shouldReleaseIdleLink(
        state: ConnectionState,
        hasOpenLease: Boolean,
        lastUserActionAtMs: Long,
        nowMs: Long,
        idleWindowMs: Long = IDLE_RELEASE_MS,
    ): Boolean {
        if (hasOpenLease) return false
        if (state != ConnectionState.Connected) return false
        if (lastUserActionAtMs <= 0L) return false
        return nowMs - lastUserActionAtMs >= idleWindowMs
    }

    /** True when the user must connect before Start can do anything. */
    fun needsConnectBeforeCharging(state: ConnectionState): Boolean =
        state != ConnectionState.Connected
}
