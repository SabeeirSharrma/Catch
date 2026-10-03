package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickerEngineTest {

    private val point = ClickPoint("p1", "Point 1", 120, 340)

    private fun config(
        cps: Float = 5f,
        maxClicks: Int = ClickerConfig.UNLIMITED,
        maxMinutes: Int = ClickerConfig.UNLIMITED,
        withPoint: Boolean = true,
    ) = ClickerConfig(
        cps = cps,
        maxClicks = maxClicks,
        maxMinutes = maxMinutes,
        points = if (withPoint) listOf(point) else emptyList(),
        activePointId = if (withPoint) point.id else null,
    )

    @Test
    fun `cannot start without a calibration point`() {
        val engine = ClickerEngine(config(withPoint = false))
        assertFalse(engine.start(0L))
        assertEquals(LoopStatus.OFF, engine.status)
        assertTrue(engine.tick(1_000L).isEmpty())
    }

    @Test
    fun `first click is immediate then evenly spaced`() {
        val engine = ClickerEngine(config(cps = 5f)) // 200 ms interval
        assertTrue(engine.start(1_000L))

        val first = engine.tick(1_000L)
        assertEquals(1, first.size)
        assertEquals(point, (first[0] as LoopEvent.Click).point)

        assertTrue(engine.tick(1_199L).isEmpty())
        assertEquals(1, engine.tick(1_200L).size)
        assertTrue(engine.tick(1_399L).isEmpty())
        assertEquals(1, engine.tick(1_400L).size)
        assertEquals(3, engine.clicksSent)
    }

    @Test
    fun `30 cps is the hard ceiling`() {
        val fast = ClickerEngine(config(cps = 30f))
        fast.start(0L)
        assertEquals(33L, fast.intervalMs) // never below 33 ms

        val thrown = runCatching { config(cps = 30.1f) }
        assertTrue(thrown.isFailure)

        val tooFast = runCatching { config(cps = 100f) }
        assertTrue(tooFast.isFailure)
    }

    @Test
    fun `minimum rate is 0_1 cps`() {
        val slow = ClickerEngine(config(cps = 0.1f))
        slow.start(0L)
        assertEquals(10_000L, slow.intervalMs)
        assertTrue(runCatching { config(cps = 0.05f) }.isFailure)
    }

    @Test
    fun `click limit stops the loop for good`() {
        val engine = ClickerEngine(config(cps = 10f, maxClicks = 3))
        engine.start(0L)

        assertEquals(1, engine.tick(0L).size)
        val second = engine.tick(100L)
        assertEquals(1, second.size)
        val third = engine.tick(200L)
        assertEquals(2, third.size) // final click plus the stop event
        assertEquals(StopReason.CLICK_LIMIT, (third[1] as LoopEvent.Stopped).reason)
        assertEquals(LoopStatus.DONE, engine.status)

        // No further clicks, and the stop reason is not repeated.
        assertTrue(engine.tick(300L).isEmpty())
        assertEquals(3, engine.clicksSent)
    }

    @Test
    fun `time limit stops the loop`() {
        val engine = ClickerEngine(config(cps = 30f, maxMinutes = 1))
        engine.start(0L)
        assertTrue(engine.tick(59_000L).none { it is LoopEvent.Stopped })

        val events = engine.tick(60_001L)
        assertEquals(1, events.size)
        assertEquals(StopReason.TIME_LIMIT, (events[0] as LoopEvent.Stopped).reason)
        assertEquals(LoopStatus.DONE, engine.status)
    }

    @Test
    fun `pause while touching emits nothing and resume does not burst`() {
        val engine = ClickerEngine(config(cps = 5f))
        engine.start(0L)
        engine.tick(0L)

        engine.pause(100L)
        assertTrue(engine.tick(1_000L).isEmpty())
        assertTrue(engine.tick(5_000L).isEmpty())
        assertEquals(LoopStatus.PAUSED, engine.status)

        engine.resume(5_000L)
        val resumed = engine.tick(5_000L)
        assertEquals(1, resumed.size) // at most one click, no catch-up burst
        assertTrue(engine.tick(5_050L).isEmpty())
        assertEquals(1, engine.tick(5_200L).size)
    }

    @Test
    fun `halt stops everything and does not restart by itself`() {
        val engine = ClickerEngine(config(cps = 30f))
        engine.start(0L)
        engine.tick(0L)

        engine.halt(StopReason.SHIZUKU_DIED, 10L)
        assertEquals(LoopStatus.DONE, engine.status)

        val stop = engine.tick(20L)
        assertEquals(1, stop.size)
        assertEquals(StopReason.SHIZUKU_DIED, (stop[0] as LoopEvent.Stopped).reason)
        assertTrue(engine.tick(30L).isEmpty())

        // A tick long after must not resurrect the loop.
        assertTrue(engine.tick(10_000L).isEmpty())
        assertEquals(1, engine.clicksSent)
    }

    @Test
    fun `huge backlog is resynchronised instead of bursting`() {
        val engine = ClickerEngine(config(cps = 30f))
        engine.start(0L)

        val events = engine.tick(60_000L)
        assertEquals(1, events.size) // one click, not 60000/33
        assertEquals(1, engine.clicksSent)
        assertEquals(60_000L + engine.intervalMs, engine.nextDueAt)
    }

    @Test
    fun `restart after stop is a new life cycle`() {
        val engine = ClickerEngine(config(cps = 10f, maxClicks = 1))
        engine.start(0L)
        engine.tick(0L)
        assertEquals(LoopStatus.DONE, engine.status)

        assertTrue(engine.start(500L))
        assertEquals(LoopStatus.RUNNING, engine.status)
        assertEquals(0, engine.clicksSent)
    }
}
