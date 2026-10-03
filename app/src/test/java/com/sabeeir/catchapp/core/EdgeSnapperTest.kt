package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSnapperTest {

    private val bubble = 100
    private val screenW = 1000
    private val screenH = 2000
    private val topInset = 100

    private fun insets() = EdgeSnapper.Insets(top = topInset)

    @Test
    fun `clamping keeps the bubble on screen`() {
        val (x, y) = EdgeSnapper.clamp(-500f, -500f, bubble, bubble, screenW, screenH)
        assertTrue(x >= EdgeSnapper.EDGE_MARGIN_PX)
        assertTrue(y >= EdgeSnapper.EDGE_MARGIN_PX)

        val (x2, y2) = EdgeSnapper.clamp(9_999f, 9_999f, bubble, bubble, screenW, screenH)
        assertEquals(screenW - bubble - EdgeSnapper.EDGE_MARGIN_PX, x2, 0.01f)
        assertEquals(screenH - bubble - EdgeSnapper.EDGE_MARGIN_PX, y2, 0.01f)
    }

    @Test
    fun `clamping respects the status bar inset`() {
        val (_, y) = EdgeSnapper.clamp(500f, -500f, bubble, bubble, screenW, screenH, insets())
        assertTrue(y >= topInset)
    }

    @Test
    fun `settling snaps to the nearer horizontal edge`() {
        val nearLeft = EdgeSnapper.settle(10f, 800f, bubble, bubble, screenW, screenH, insets())
        assertEquals(EdgeSnapper.EDGE_MARGIN_PX, nearLeft.first, 0.01f)

        val nearRight = EdgeSnapper.settle(950f, 800f, bubble, bubble, screenW, screenH, insets())
        assertEquals(screenW - bubble - EdgeSnapper.EDGE_MARGIN_PX, nearRight.first, 0.01f)

        // Exactly in the middle: either edge is fine, left wins on ties.
        val middle = EdgeSnapper.settle(450f, 800f, bubble, bubble, screenW, screenH, insets())
        assertEquals(EdgeSnapper.EDGE_MARGIN_PX, middle.first, 0.01f)
    }

    @Test
    fun `snapped position never lands outside the clamped range`() {
        for (rawX in listOf(-100f, 0f, 300f, 700f, 10_000f)) {
            val (x, _) = EdgeSnapper.settle(rawX, 500f, bubble, bubble, screenW, screenH, insets())
            assertTrue(x >= EdgeSnapper.EDGE_MARGIN_PX - 0.01f)
            assertTrue(x <= screenW - bubble - EdgeSnapper.EDGE_MARGIN_PX + 0.01f)
        }
    }

    @Test
    fun `tiny screen does not produce inverted bounds`() {
        val (x, y) = EdgeSnapper.settle(500f, 500f, bubble, bubble, 80, 80)
        assertTrue(x.isFinite() && y.isFinite())
        assertTrue(x >= 0f && y >= 0f)
    }
}
