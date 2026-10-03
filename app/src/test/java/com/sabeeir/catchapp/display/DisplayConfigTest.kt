package com.sabeeir.catchapp.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayConfigTest {

    @Test
    fun `defaults match the spec starting point`() {
        val config = DisplayConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(160, config.dpi)
        assertEquals(16f / 9f, config.aspect, 0.01f)
    }

    @Test
    fun `out of range values are rejected`() {
        assertTrue(runCatching { DisplayConfig(width = 100) }.isFailure)
        assertTrue(runCatching { DisplayConfig(height = 99_999) }.isFailure)
        assertTrue(runCatching { DisplayConfig(dpi = 10) }.isFailure)
        assertTrue(runCatching { DisplayConfig(dpi = 1_000) }.isFailure)
    }

    @Test
    fun `sanitized clamps instead of throwing`() {
        val clamped = DisplayConfig.sanitized(width = 10, height = 10_000, dpi = 1)
        assertEquals(DisplayConfig.MIN_SIZE, clamped.width)
        assertEquals(DisplayConfig.MAX_SIZE, clamped.height)
        assertEquals(DisplayConfig.MIN_DPI, clamped.dpi)

        val kept = DisplayConfig.sanitized(1280, 720, 160)
        assertEquals(DisplayConfig(1280, 720, 160), kept)
    }
}
