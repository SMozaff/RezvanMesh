package com.rezvani.mesh.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the private-channel join throttle.
 *
 * The throttle exists because a failed join costs a deliberate 210k-iteration
 * PBKDF2. That cost is what protects a stolen database, and it is also what
 * makes unbounded attempts a denial-of-service vector against the device doing
 * the defending. These tests use an injected clock so the backoff is
 * verifiable without sleeping.
 */
class JoinThrottleTest {

    /** Manually advanced clock. */
    private var now = 0L
    private fun clock() = now

    private fun throttle(
        maxAttempts: Int = 3,
        baseCooldownMs: Long = 5_000L,
        maxCooldownMs: Long = 300_000L
    ) = JoinThrottle(
        maxAttempts = maxAttempts,
        baseCooldownMs = baseCooldownMs,
        maxCooldownMs = maxCooldownMs,
        clock = { now }
    )

    @Test
    fun `an unknown channel is always allowed`() {
        val t = throttle()
        assertTrue(t.tryAttempt(1))
        assertTrue(t.tryAttempt(999))
    }

    /** Two typos should be free -- people mistype. */
    @Test
    fun `attempts below the threshold are not throttled`() {
        val t = throttle(maxAttempts = 3)
        repeat(2) {
            assertTrue(t.tryAttempt(1))
            t.recordFailure(1)
        }
        assertTrue("two failures must not lock the user out", t.tryAttempt(1))
    }

    @Test
    fun `the threshold attempt locks the channel out`() {
        val t = throttle(maxAttempts = 3)
        repeat(3) {
            assertTrue(t.tryAttempt(1))
            t.recordFailure(1)
        }
        assertFalse("the third failure must trip the lockout", t.tryAttempt(1))
    }

    @Test
    fun `the lockout expires`() {
        val t = throttle(maxAttempts = 3, baseCooldownMs = 5_000L)
        repeat(3) { t.recordFailure(1) }
        assertFalse(t.tryAttempt(1))
        now += 4_999L
        assertFalse("still inside the cooldown window", t.tryAttempt(1))
        now += 2L
        assertTrue("must be allowed once the cooldown elapses", t.tryAttempt(1))
    }

    /**
     * Smallest wait, in ms, after which [channelId] becomes attemptable again.
     *
     * Probed rather than computed, so the test asserts the real observable
     * behaviour without duplicating the implementation's own arithmetic (and
     * so an off-by-one in either the exponent or the cap shows up here).
     */
    private fun waitNeeded(t: JoinThrottle, channelId: Int, limitMs: Long = 60_000L): Long {
        val start = now
        var waited = 0L
        while (waited <= limitMs) {
            if (t.tryAttempt(channelId)) return waited
            waited += 1
            now = start + waited
        }
        throw AssertionError("channel $channelId never unlocked within ${limitMs}ms")
    }

    /** Repeated abuse must keep getting slower, but never past the cap. */
    @Test
    fun `backoff grows with consecutive failures and is capped`() {
        val maxCooldown = 8_000L
        val t = throttle(maxAttempts = 2, baseCooldownMs = 1_000L, maxCooldownMs = maxCooldown)

        // Trip the first lockout (2 failures) and measure it.
        repeat(2) { t.recordFailure(1) }
        val first = waitNeeded(t, 1)
        assertEquals(1_000L, first)

        // Now fail past the threshold twice more; the wait must grow.
        repeat(2) { t.recordFailure(1) }
        val second = waitNeeded(t, 1)
        assertTrue(
            "backoff must grow: $second should exceed $first",
            second > first
        )

        // Keep failing. The wait must saturate at the cap, never exceed it.
        repeat(40) { t.recordFailure(1) }
        val capped = waitNeeded(t, 1, limitMs = maxCooldown * 4)
        assertEquals(maxCooldown, capped)

        // And a very large failure count must not wrap the shift to a
        // zero-length cooldown.
        repeat(1_000) { t.recordFailure(1) }
        assertTrue(waitNeeded(t, 1, limitMs = maxCooldown * 4) > 0L)
    }

    /** One channel being attacked must not lock the user out of their others. */
    @Test
    fun `throttling is per channel`() {
        val t = throttle(maxAttempts = 2)
        repeat(2) { t.recordFailure(1) }
        assertFalse("channel 1 is locked", t.tryAttempt(1))
        assertTrue("an unrelated channel must be unaffected", t.tryAttempt(2))
    }

    @Test
    fun `a success clears the failure history`() {
        val t = throttle(maxAttempts = 3)
        repeat(3) { t.recordFailure(1) }
        assertFalse(t.tryAttempt(1))
        t.recordSuccess(1)
        assertTrue("a successful join must forgive earlier typos", t.tryAttempt(1))
    }

    @Test
    fun `reset clears all state`() {
        val t = throttle(maxAttempts = 2)
        repeat(2) { t.recordFailure(1) }
        repeat(2) { t.recordFailure(2) }
        assertFalse(t.tryAttempt(1))
        assertFalse(t.tryAttempt(2))
        t.reset()
        assertTrue(t.tryAttempt(1))
        assertTrue(t.tryAttempt(2))
    }

    @Test
    fun `tryAttempt does not itself count as a failure`() {
        val t = throttle(maxAttempts = 2)
        repeat(50) { assertTrue(t.tryAttempt(1)) }
        assertTrue("polling must not trip the lockout", t.tryAttempt(1))
    }
}
