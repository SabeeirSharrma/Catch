package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectorGuardTest {

    private fun liveGuard(): InjectorGuard = InjectorGuard(
        expectedDisplayId = 42,
        displayAlive = true,
        shizukuAlive = true,
        stopped = false,
    )

    @Test
    fun `healthy session injects to its own display`() {
        val guard = liveGuard()
        assertEquals(InjectorGuard.Refusal.OK, guard.check(42))
        assertTrue(guard.canInject(42))
    }

    @Test
    fun `the real screen is always refused`() {
        val guard = liveGuard()
        assertFalse(guard.canInject(0))
        assertEquals(InjectorGuard.Refusal.ID_MISMATCH, guard.check(0))
        assertFalse(guard.canInject(-1))
        assertFalse(guard.canInject(null))
    }

    @Test
    fun `another display id is refused`() {
        val guard = liveGuard()
        assertFalse(guard.canInject(7))
        assertFalse(guard.canInject(43))
    }

    @Test
    fun `dead shizuku refuses everything`() {
        val guard = liveGuard()
        guard.shizukuLost()
        assertEquals(InjectorGuard.Refusal.SHIZUKU_DEAD, guard.check(42))
        assertFalse(guard.canInject(42))

        guard.shizukuRestored()
        assertTrue(guard.canInject(42))
    }

    @Test
    fun `released display refuses everything until a new session starts`() {
        val guard = liveGuard()
        guard.displayLost()
        assertEquals(InjectorGuard.Refusal.DISPLAY_GONE, guard.check(42))

        guard.sessionStopped()
        assertEquals(InjectorGuard.Refusal.STOPPED, guard.check(42))
        assertEquals(null, guard.expectedDisplayId)

        guard.sessionStarted(9)
        guard.shizukuRestored()
        assertTrue(guard.canInject(9))
        assertFalse(guard.canInject(42)) // old id must not be reusable
    }

    @Test
    fun `stopped session refuses even with a stale display id`() {
        val guard = InjectorGuard(expectedDisplayId = 42, displayAlive = true, shizukuAlive = true)
        assertEquals(InjectorGuard.Refusal.STOPPED, guard.check(42))
    }
}
