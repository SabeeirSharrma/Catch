package com.sabeeir.catchapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {

    @Test
    fun `report names the app, the version, the time and the exception`() {
        val text = CrashLog.render(IllegalStateException("boom"), "0.1.2 (3)", 1_700_000_000_000)

        assertTrue(text, text.contains("Catch 0.1.2 (3)"))
        assertTrue(text, text.contains("1700000000000"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text, text.contains("\tat ")) // a real stack, not just a class name
    }

    @Test
    fun `report keeps the whole cause chain`() {
        val wrapper = IllegalStateException("wrapper", IllegalStateException("root cause"))

        val text = CrashLog.render(wrapper, "0.1.2 (3)", 0)

        assertTrue(text, text.contains("wrapper"))
        assertTrue(text, text.contains("Caused by:"))
        assertTrue(text, text.contains("root cause"))
    }

    @Test
    fun `clip leaves a trace that already fits untouched`() {
        val trace = "short trace"
        assertEquals(trace, CrashLog.clip(trace))
    }

    @Test
    fun `clip keeps both ends of an oversized trace`() {
        val trace = "HEAD\n" + "m".repeat(CrashLog.MAX_CHARS * 3) + "\nTAIL"

        val clipped = CrashLog.clip(trace)

        assertTrue(clipped, clipped.startsWith("HEAD"))
        assertTrue(clipped, clipped.endsWith("TAIL"))
        assertTrue(clipped, clipped.contains("characters elided"))
        assertTrue(clipped, clipped.length < trace.length)
        // The clip marker must not push the file past its budget by any meaningful amount.
        assertTrue(clipped, clipped.length <= CrashLog.MAX_CHARS + 256)
    }

    @Test
    fun `a report never claims more room than the clip allows`() {
        val huge = IllegalArgumentException("x".repeat(CrashLog.MAX_CHARS * 4))

        val text = CrashLog.render(huge, "0.1.2 (3)", 42)

        assertTrue(text, text.length <= CrashLog.MAX_CHARS + 512)
        assertTrue(text, text.contains("characters elided"))
    }

    @Test
    fun `rendered reports are non-empty for a bare throwable`() {
        val text = CrashLog.render(RuntimeException(), "unknown version", 1)
        assertFalse(text.isBlank())
        assertTrue(text, text.contains("java.lang.RuntimeException"))
    }
}
