package org.superdro.core.readout.input

/**
 * One probe port `p` or `q`.
 *
 * 1. The active raw value is 1. A normally closed contact makes the active raw value 0.
 * 2. A change to active needs one of two conditions: the debounce is 0, or more than the
 *    debounce time passed since the last accepted activation. A negative time difference also
 *    accepts the activation.
 * 3. A change to not active is accepted at once.
 * 4. Watchdog: period `T = max(5 × debounce, 1000 ms)`. If the state is active and no active
 *    message came in a period, the state changes to not active. Thus the state goes back
 *    between `T` and `2T` after the last `P1;`.
 *
 * All times are the message time stamps or the session clock of a tick.
 */
class ProbeInput(val port: Char, settings: ProbeSettings = ProbeSettings()) {

    /** The contact type and the debounce of the adapter. */
    var settings: ProbeSettings = settings

    /** True while the probe is active. */
    var active: Boolean = false
        private set

    private var seenActive = false
    private var lastAccepted: Long? = null
    private var periodStart: Long? = null

    /**
     * Processes the raw value [raw] (0 or 1) at the time [timeMs]. Gives true when the input
     * accepts a change to active.
     */
    fun onMessage(raw: Int, timeMs: Long): Boolean {
        advanceTo(timeMs)
        val messageActive = raw == settings.activeValue
        seenActive = messageActive
        if (active == messageActive) return false
        if (!messageActive) {
            active = false
            return false
        }
        val debounce = settings.debounceMs
        val last = lastAccepted
        val accept = debounce == 0L || last == null || timeMs - last < 0L || timeMs - last > debounce
        if (!accept) return false
        active = true
        lastAccepted = timeMs
        return true
    }

    /**
     * Runs the watchdog up to the time [nowMs]. Gives true when the watchdog changed the state to
     * not active.
     */
    fun advanceTo(nowMs: Long): Boolean {
        val start = periodStart
        if (start == null) {
            periodStart = nowMs
            return false
        }
        val period = settings.watchdogPeriodMs
        if (nowMs - start < period) return false
        val periods = (nowMs - start) / period
        var cleared = false
        // Only the first two period ends can change the state: after them, the flag is false.
        repeat(minOf(periods, 2L).toInt()) {
            if (active && !seenActive) {
                active = false
                cleared = true
            }
            seenActive = false
        }
        periodStart = start + periods * period
        return cleared
    }

    /** Starts again at the start of a connection: not active, no debounce time, no period. */
    fun reset() {
        active = false
        seenActive = false
        lastAccepted = null
        periodStart = null
    }
}
