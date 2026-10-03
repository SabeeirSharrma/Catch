package com.sabeeir.catchapp.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The overlay is built from a Service, which is not a UiContext: the window manager is allowed
 * to refuse `currentWindowMetrics` there (documented `UnsupportedOperationException`) and to
 * hand back null insets. These are the fallbacks that keep that refusal from killing the
 * foreground service.
 */
class ScreenMetricsTest {

    @Test
    fun `window metrics win whenever the platform provides them`() {
        val bounds = ScreenMetrics.bounds(ScreenMetrics.Bounds(1080, 2400), 720, 1280)
        assertEquals(1080, bounds.width)
        assertEquals(2400, bounds.height)
    }

    @Test
    fun `a refused window metric falls back to the display metrics`() {
        val bounds = ScreenMetrics.bounds(null, 1080, 2400)
        assertEquals(1080, bounds.width)
        assertEquals(2400, bounds.height)
    }

    @Test
    fun `empty window metrics count as a refusal`() {
        val bounds = ScreenMetrics.bounds(ScreenMetrics.Bounds(0, 0), 720, 1280)
        assertEquals(720, bounds.width)
        assertEquals(1280, bounds.height)
    }

    @Test
    fun `bounds are never zero so the bubble always has somewhere to sit`() {
        val bounds = ScreenMetrics.bounds(null, 0, -5)
        assertEquals(1, bounds.width)
        assertEquals(1, bounds.height)
    }

    @Test
    fun `missing insets default to zero instead of throwing`() {
        assertEquals(0, ScreenMetrics.topInset(null))
        assertEquals(120, ScreenMetrics.topInset(120))
    }

    @Test
    fun `a negative inset is clamped to zero`() {
        assertEquals(0, ScreenMetrics.topInset(-40))
    }
}
