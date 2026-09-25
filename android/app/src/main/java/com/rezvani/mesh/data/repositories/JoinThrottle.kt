package com.rezvani.mesh.data.repositories

/**
 * Per-channel throttle for private-channel password attempts.
 *
 * Why this exists: [ChannelPasswordHasher] is deliberately expensive (210k
 * PBKDF2 iterations) so a leaked database cannot be brute-forced cheaply. That
 * same cost means an unbounded number of *failed* attempts is itself an
 * attack -- a script driving the UI in a loop turns the device into something
 * burning CPU on someone else's behalf, and on a battery-powered mesh node that
 * is a real denial of service against the mesh.
 *
 * # Design
 *
 * Lockout grows with consecutive failures and is per channel, so a user fumbling
 * one password does not lock themselves out of every other channel. A single
 * success clears the counter.
 *
 * This is deliberately **in memory**. Persisting attempt counts would mean a
 * new table and a migration, and it would make the throttle bypassable by
 * clearing app data -- at which point the attacker is already past every other
 * control. The goal is to make bulk guessing impractical from one device, not
 * to be a distributed rate limit.
 *
 * Not thread-safe by itself. [ChannelRepository] owns a single instance and only
 * touches it while holding a coroutine `Mutex`.
 */
class JoinThrottle(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseCooldownMs: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class State(
        var failures: Int = 0,
        var lockedUntilMs: Long = 0L
    )

    private val states = HashMap<Int, State>()

    /**
     * Whether an attempt may be made right now. Does not record anything --
     * the caller records the outcome with [recordFailure] / [recordSuccess].
     */
    fun tryAttempt(channelId: Int): Boolean {
        val state = states[channelId] ?: return true
        return clock() >= state.lockedUntilMs
    }

    /** Record a failed attempt, extending the lockout if it trips the threshold. */
    fun recordFailure(channelId: Int) {
        val now = clock()
        val state = states.getOrPut(channelId) { State() }
        state.failures++
        if (state.failures >= maxAttempts) {
            // Exponential backoff, capped, so a determined attacker still gets
            // throttled but a genuine user who mistypes twice is not punished
            // for more than a few seconds.
            val exponent = (state.failures - maxAttempts).coerceAtMost(16)
            val cooldown = (baseCooldownMs shl exponent).coerceAtMost(maxCooldownMs)
            state.lockedUntilMs = now + cooldown
        }
    }

    /** Clear the failure history for a channel after a successful join. */
    fun recordSuccess(channelId: Int) {
        states.remove(channelId)
    }

    /** Drop all state, e.g. when the user signs out or the DB is recreated. */
    fun reset() {
        states.clear()
    }

    companion object {
        /**
         * Attempts allowed before the first lockout.
         *
         * Two mistakes should be free -- people mistype. The third trips it.
         */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** First lockout is short; the point is friction, not punishment. */
        const val DEFAULT_BASE_COOLDOWN_MS = 5_000L

        /** Ceiling on the exponential backoff. */
        const val DEFAULT_MAX_COOLDOWN_MS = 5 * 60_000L
    }
}
