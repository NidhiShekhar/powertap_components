package com.drivool.iot.powertap.session

/**
 * Suppresses charger frames that repeat an observation already recorded.
 *
 * The firmware stamps a message id once, when a command leaves its transmit
 * queue (`Tx_Start` in `esp/mqtt.cpp`), then re-sends that same queue entry
 * every `RETRY_DELAY_MS` until a matching CallResult arrives. The payload is
 * rebuilt from live meter globals on each retry, but the action and the id are
 * not. So a Heartbeat queued while the relay was open keeps arriving — with a
 * fresh receive timestamp — well after charging has started, and taken as new
 * evidence it ends a session that is still running.
 *
 * The key is (id, action) rather than id alone. `StartTransaction` and
 * `StopTransaction` are composed outside the queue and reuse the counter's
 * current value, so they legitimately share an id with the frame before them.
 */
class RetransmitFilter(private val capacity: Int = DEFAULT_CAPACITY) {

    private val seen = LinkedHashSet<String>()

    /**
     * @return true when this frame carries an observation not seen before.
     *   Frames with no id cannot be matched against anything, so they count.
     */
    fun accept(messageId: String, action: String): Boolean {
        val id = messageId.trim()
        if (id.isEmpty() || action.isEmpty()) return true
        if (!seen.add("$id|$action")) return false
        if (seen.size > capacity) {
            with(seen.iterator()) {
                next()
                remove()
            }
        }
        return true
    }

    /** Forget everything. Only correct when the charger restarts its counter. */
    fun reset() {
        seen.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
