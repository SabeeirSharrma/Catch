package com.sabeeir.catchapp.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSequenceTest {

    @Test
    fun `steps run in the order from the spec`() = runBlocking {
        val executed = mutableListOf<StopSequence.Step>()
        val failures = StopSequence { executed += it }.run()

        assertEquals(StopSequence.ORDER, executed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `order is halt tools, force stop roblox, release display, stop service`() {
        assertEquals(
            listOf(
                StopSequence.Step.HALT_TOOLS,
                StopSequence.Step.FORCE_STOP_ROBLOX,
                StopSequence.Step.RELEASE_DISPLAY,
                StopSequence.Step.STOP_SERVICE,
            ),
            StopSequence.ORDER,
        )
    }

    @Test
    fun `a failing step does not prevent the later ones`() = runBlocking {
        val executed = mutableListOf<StopSequence.Step>()
        val failures = StopSequence { step ->
            executed += step
            if (step == StopSequence.Step.FORCE_STOP_ROBLOX) error("shizuku gone")
        }.run()

        assertEquals(StopSequence.ORDER, executed)
        assertEquals(1, failures.size)
        assertEquals(StopSequence.Step.FORCE_STOP_ROBLOX, failures[0].step)
        assertTrue(failures[0].message.contains("shizuku gone"))
    }

    @Test
    fun `every failure is reported`() = runBlocking {
        val failures = StopSequence { error("boom") }.run()
        assertEquals(StopSequence.ORDER.size, failures.size)
        assertTrue(failures.all { it.message == "boom" })
    }
}
