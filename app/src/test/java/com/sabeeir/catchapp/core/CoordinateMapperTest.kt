package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun `exact fit maps corners correctly`() {
        val rect = CoordinateMapper.fitRect(1280, 720, 1280, 720)!!
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)

        assertEquals(0 to 0, CoordinateMapper.toDisplay(0f, 0f, rect, 1280, 720))
        val bottomRight = CoordinateMapper.toDisplay(1279.9f, 719.9f, rect, 1280, 720)
        assertEquals(1279, bottomRight?.first)
        assertEquals(719, bottomRight?.second)
    }

    @Test
    fun `wide display is letterboxed vertically in a square view`() {
        val rect = CoordinateMapper.fitRect(1000, 1000, 1280, 720)!!
        assertEquals(1000f, rect.width)
        assertTrue(rect.height < 1000f)
        assertTrue(rect.top > 0f)

        // Touching the letterbox must be rejected, never clamped onto the image.
        assertNull(CoordinateMapper.toDisplay(500f, 1f, rect, 1280, 720))
        assertNotNull(CoordinateMapper.toDisplay(500f, rect.top + 1f, rect, 1280, 720))
    }

    @Test
    fun `tall display is letterboxed horizontally`() {
        val rect = CoordinateMapper.fitRect(500, 500, 480, 1080)!!
        assertTrue(rect.width < 500f)
        assertTrue(rect.left > 0f)
        assertNull(CoordinateMapper.toDisplay(1f, 250f, rect, 480, 1080))
    }

    @Test
    fun `scaling into the display is proportional`() {
        // View is exactly half the display: view (10,10) -> display (20,20)
        val rect = CoordinateMapper.fitRect(640, 360, 1280, 720)!!
        assertEquals(20 to 20, CoordinateMapper.toDisplay(10f, 10f, rect, 1280, 720))
    }

    @Test
    fun `degenerate geometry is rejected`() {
        assertNull(CoordinateMapper.fitRect(0, 100, 1280, 720))
        assertNull(CoordinateMapper.fitRect(100, 0, 1280, 720))
        assertNull(CoordinateMapper.fitRect(100, 100, 0, 720))
        assertNull(CoordinateMapper.toDisplay(10f, 10f, null, 1280, 720))
        assertNull(CoordinateMapper.toDisplay(10f, 10f, CoordinateMapper.ContentRect(0f, 0f, 1f, 1f), 0, 0))
    }

    @Test
    fun `touch exactly on the right edge of the content is inside, one pixel further is not`() {
        val rect = CoordinateMapper.fitRect(1000, 500, 1000, 500)!!
        assertTrue(rect.contains(999.5f, 100f))
        assertFalse(rect.contains(1000f, 100f))
        assertFalse(rect.contains(-0.5f, 100f))
    }
}
