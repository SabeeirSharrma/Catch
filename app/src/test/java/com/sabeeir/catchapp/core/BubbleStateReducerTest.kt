package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStateReducerTest {

    private fun signals(
        shizuku: Boolean = true,
        roblox: Boolean = true,
        display: Boolean = true,
        loop: Boolean = false,
        mirror: Boolean = false,
    ) = ServiceSignals(shizuku, roblox, display, loop, mirror)

    @Test
    fun `healthy hidden session is idle`() {
        assertEquals(BubbleState.IDLE, BubbleStateReducer.reduce(signals()))
    }

    @Test
    fun `healthy shown session is running`() {
        assertEquals(BubbleState.RUNNING, BubbleStateReducer.reduce(signals(mirror = true)))
    }

    @Test
    fun `loop indicator wins over running and idle`() {
        assertEquals(BubbleState.LOOP_ON, BubbleStateReducer.reduce(signals(loop = true)))
        assertEquals(BubbleState.LOOP_ON, BubbleStateReducer.reduce(signals(loop = true, mirror = true)))
    }

    @Test
    fun `shizuku down outranks every other state`() {
        val broken = signals(shizuku = false, roblox = false, display = false, loop = true)
        assertEquals(BubbleState.SHIZUKU_DOWN, BubbleStateReducer.reduce(broken))
    }

    @Test
    fun `lost display outranks dead roblox`() {
        assertEquals(
            BubbleState.DISPLAY_LOST,
            BubbleStateReducer.reduce(signals(display = false, roblox = false, loop = true)),
        )
    }

    @Test
    fun `dead roblox is reported even while looping`() {
        assertEquals(BubbleState.ROBLOX_DEAD, BubbleStateReducer.reduce(signals(roblox = false, loop = true)))
    }

    @Test
    fun `health requires all three signals`() {
        assertTrue(BubbleStateReducer.isHealthy(signals()))
        assertFalse(BubbleStateReducer.isHealthy(signals(shizuku = false)))
        assertFalse(BubbleStateReducer.isHealthy(signals(roblox = false)))
        assertFalse(BubbleStateReducer.isHealthy(signals(display = false)))
    }

    @Test
    fun `every state has a user facing description`() {
        for (state in BubbleState.values()) {
            assertTrue(BubbleStateReducer.describe(state).isNotBlank())
        }
    }
}
