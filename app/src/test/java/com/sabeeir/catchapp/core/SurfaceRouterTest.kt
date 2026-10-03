package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceRouterTest {

    @Test
    fun `nothing is routed initially`() {
        val router = SurfaceRouter<String>()
        assertFalse(router.hasSurface)
        assertNull(router.activeOrNull())
    }

    @Test(expected = IllegalStateException::class)
    fun `reading before the first swap throws instead of returning null`() {
        val router = SurfaceRouter<String>()
        router.active
    }

    @Test
    fun `first swap returns null as previous`() {
        val router = SurfaceRouter<String>()
        assertNull(router.swapTo("sink"))
        assertEquals("sink", router.active)
        assertTrue(router.hasSurface)
    }

    @Test
    fun `mirror swaps return the previous surface`() {
        val router = SurfaceRouter<String>()
        router.swapTo("sink")
        assertEquals("sink", router.swapTo("mirror"))
        assertEquals("mirror", router.active)
        assertEquals("mirror", router.swapTo("sink"))
    }

    @Test
    fun `the active surface is never null even after repeated swaps`() {
        val router = SurfaceRouter<String>()
        repeat(10) { i -> router.swapTo(if (i % 2 == 0) "sink" else "mirror") }
        assertTrue(router.active.isNotEmpty())
    }
}
