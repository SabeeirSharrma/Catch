package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiIdleEngineTest {

    private val point = ClickPoint("p1", "Point 1", 50, 60)

    private fun engine(minutes: Int = 5, withPoint: Boolean = true) = AntiIdleEngine(
        AntiIdleConfig(enabled = true, intervalMinutes = minutes),
    ) { if (withPoint) point else null }

    @Test
    fun `interval is always well under the 20 minute kick`() {
        assertEquals(5, AntiIdleConfig.DEFAULT_MINUTES)
        assertTrue(AntiIdleConfig.MAX_MINUTES < 20)
        assertTrue(runCatching { AntiIdleConfig(enabled = true, intervalMinutes = 20) }.isFailure)
        assertTrue(runCatching { AntiIdleConfig(enabled = true, intervalMinutes = 0) }.isFailure)
    }

    @Test
    fun `first nudge happens after the interval, not immediately`() {
        val engine = engine()
        assertTrue(engine.start(0L))
        assertTrue(engine.tick(0L).isEmpty())
        assertTrue(engine.tick(4 * 60_000L).isEmpty())

        val events = engine.tick(5 * 60_000L)
        assertEquals(1, events.size)
        val click = events[0] as LoopEvent.Click
        assertEquals(point, click.point)
    }

    @Test
    fun `runs on its own cadence afterwards`() {
        val engine = engine(minutes = 2)
        engine.start(0L)
        engine.tick(2 * 60_000L)
        assertTrue(engine.tick(3 * 60_000L).isEmpty())
        assertEquals(1, engine.tick(4 * 60_000L).size)
        assertEquals(2, engine.tapsSent)
    }

    @Test
    fun `cannot start without a point`() {
        val engine = engine(withPoint = false)
        assertFalse(engine.start(0L))
        assertEquals(LoopStatus.OFF, engine.status)
    }

    @Test
    fun `halt is final and reported once`() {
        val engine = engine()
        engine.start(0L)
        engine.halt(StopReason.ROBLOX_DIED)

        val stop = engine.tick(10 * 60_000L)
        assertEquals(1, stop.size)
        assertEquals(StopReason.ROBLOX_DIED, (stop[0] as LoopEvent.Stopped).reason)
        assertTrue(engine.tick(20 * 60_000L).isEmpty())
        assertEquals(0, engine.tapsSent)
    }
}
