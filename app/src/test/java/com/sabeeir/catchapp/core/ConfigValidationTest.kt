package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidationTest {

    @Test
    fun `clicker rate bounds are enforced by the constructor`() {
        assertTrue(runCatching { ClickerConfig(cps = 0f) }.isFailure)
        assertTrue(runCatching { ClickerConfig(cps = 0.09f) }.isFailure)
        assertTrue(runCatching { ClickerConfig(cps = 30.01f) }.isFailure)
        assertTrue(runCatching { ClickerConfig(cps = Float.NaN) }.isFailure)
        assertTrue(runCatching { ClickerConfig(cps = Float.POSITIVE_INFINITY) }.isFailure)
        ClickerConfig(cps = ClickerConfig.MIN_CPS)
        ClickerConfig(cps = ClickerConfig.MAX_CPS)
    }

    @Test
    fun `clampCps keeps UI input inside the hard cap`() {
        assertEquals(30f, ClickerConfig.clampCps(1_000f))
        assertEquals(0.1f, ClickerConfig.clampCps(0f))
        assertEquals(12.5f, ClickerConfig.clampCps(12.5f), 0.001f)
    }

    @Test
    fun `limits are non negative`() {
        assertTrue(runCatching { ClickerConfig(maxClicks = -1) }.isFailure)
        assertTrue(runCatching { ClickerConfig(maxMinutes = -5) }.isFailure)
        assertEquals(0, ClickerConfig.UNLIMITED)
    }

    @Test
    fun `duplicate point ids are rejected`() {
        val a = ClickPoint("p", "A", 1, 1)
        val b = ClickPoint("p", "B", 2, 2)
        assertTrue(runCatching { ClickerConfig(points = listOf(a, b)) }.isFailure)
    }

    @Test
    fun `active point must exist when specified`() {
        val a = ClickPoint("p", "A", 1, 1)
        assertTrue(runCatching { ClickerConfig(points = listOf(a), activePointId = "nope") }.isFailure)
        val config = ClickerConfig(points = listOf(a), activePointId = "p")
        assertEquals(a, config.activePoint)
    }

    @Test
    fun `active point falls back to the first saved point`() {
        val a = ClickPoint("p1", "A", 1, 1)
        val b = ClickPoint("p2", "B", 2, 2)
        val config = ClickerConfig(points = listOf(a, b), activePointId = null)
        assertEquals(a, config.activePoint)
    }

    @Test
    fun `click points need non negative coordinates`() {
        assertTrue(runCatching { ClickPoint("", "label", 0, 0) }.isFailure)
        assertTrue(runCatching { ClickPoint("id", "label", -1, 0) }.isFailure)
        assertTrue(runCatching { ClickPoint("id", "label", 0, -1) }.isFailure)
        ClickPoint("id", "label", 0, 0)
    }

    @Test
    fun `anti idle interval is clamped by the constructor`() {
        assertTrue(runCatching { AntiIdleConfig(intervalMinutes = 0) }.isFailure)
        assertTrue(runCatching { AntiIdleConfig(intervalMinutes = 20) }.isFailure)
        AntiIdleConfig(intervalMinutes = AntiIdleConfig.MAX_MINUTES)
        assertEquals(19, AntiIdleConfig.clampMinutes(500))
        assertEquals(1, AntiIdleConfig.clampMinutes(-3))
        assertEquals(7, AntiIdleConfig.clampMinutes(7))
    }

    @Test
    fun `anti idle interval converts to milliseconds`() {
        assertEquals(5 * 60_000L, AntiIdleConfig(intervalMinutes = 5).intervalMs)
    }
}
