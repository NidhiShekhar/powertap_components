package com.drivool.iot.powertap.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charger re-sends unacknowledged frames every 5s, verbatim apart from the
 * meter readings. Counting a retry as a fresh observation is what let a
 * heartbeat queued before Start close the session that followed it.
 */
class RetransmitFilterTest {

    private val filter = RetransmitFilter()

    @Test
    fun firstSightOfAFrameIsEvidence() {
        assertTrue(filter.accept("1048578", "StatusNotification"))
    }

    @Test
    fun sameFrameResentIsNotNewEvidence() {
        assertTrue(filter.accept("1048579", "Heartbeat"))
        assertFalse(filter.accept("1048579", "Heartbeat"))
        assertFalse(filter.accept("1048579", "Heartbeat"))
    }

    @Test
    fun frameComposedOutsideTheQueueReusesTheIdAndStillCounts() {
        // StartTransaction and StopTransaction are built with the counter's
        // current value rather than a fresh one, so they share an id with the
        // queued frame before them. Keying on the id alone would swallow them.
        assertTrue(filter.accept("1048578", "StatusNotification"))
        assertTrue(filter.accept("1048578", "StartTransaction"))
        assertTrue(filter.accept("1048579", "Heartbeat"))
        assertTrue(filter.accept("1048579", "StopTransaction"))
    }

    @Test
    fun aRetryAfterAnInterveningFrameIsStillARetry() {
        // The sequence that ended a live session: an idle frame, the start it
        // preceded, then the idle frame again on its 5s retry timer.
        assertTrue(filter.accept("1048578", "StatusNotification"))
        assertTrue(filter.accept("1048578", "StartTransaction"))
        assertFalse(filter.accept("1048578", "StatusNotification"))
    }

    @Test
    fun framesWithoutAnIdAlwaysCount() {
        // Nothing to match them against, and dropping real evidence is worse
        // than letting a duplicate through.
        assertTrue(filter.accept("", "Heartbeat"))
        assertTrue(filter.accept("", "Heartbeat"))
        assertTrue(filter.accept("  ", "MeterValues"))
    }

    @Test
    fun resetLetsRecycledIdsThroughAgain() {
        assertTrue(filter.accept("1048577", "Heartbeat"))
        assertFalse(filter.accept("1048577", "Heartbeat"))
        filter.reset()
        assertTrue(filter.accept("1048577", "Heartbeat"))
    }

    @Test
    fun oldIdsAreForgottenOnceCapacityIsReached() {
        val small = RetransmitFilter(capacity = 2)
        assertTrue(small.accept("1", "Heartbeat"))
        assertTrue(small.accept("2", "Heartbeat"))
        assertTrue(small.accept("3", "Heartbeat"))
        assertTrue(small.accept("1", "Heartbeat"))
        assertFalse(small.accept("3", "Heartbeat"))
    }
}
