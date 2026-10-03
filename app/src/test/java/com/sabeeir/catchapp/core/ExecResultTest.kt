package com.sabeeir.catchapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecResultTest {

    @Test
    fun `round trip preserves exit code, stdout and stderr`() {
        val raw = ExecResult.encode(2, "line one\nline two", "warning: boom")
        val decoded = ExecResult.decode(raw, "am start")

        assertEquals(2, decoded.exitCode)
        assertEquals("line one\nline two", decoded.stdout)
        assertEquals("warning: boom", decoded.stderr)
        assertEquals("am start", decoded.command)
        assertFalse(decoded.isSuccess)
    }

    @Test
    fun `unicode and separator characters survive the framing`() {
        val stdout = "unicode: 日本語 ✅ | and a pipe | and another"
        val raw = ExecResult.encode(0, stdout, "")
        val decoded = ExecResult.decode(raw, "test")

        assertEquals(0, decoded.exitCode)
        assertEquals(stdout, decoded.stdout)
        assertEquals("", decoded.stderr)
        assertTrue(decoded.isSuccess)
    }

    @Test
    fun `large output survives`() {
        val stdout = "x".repeat(500_000)
        val decoded = ExecResult.decode(ExecResult.encode(0, stdout, "err"), "big")
        assertEquals(500_000, decoded.stdout.length)
        assertEquals("err", decoded.stderr)
    }

    @Test
    fun `malformed responses become errors instead of throwing`() {
        assertEquals(Int.MIN_VALUE, ExecResult.decode(null, "cmd").exitCode)
        assertEquals(Int.MIN_VALUE, ExecResult.decode("", "cmd").exitCode)
        assertEquals(Int.MIN_VALUE, ExecResult.decode("garbage", "cmd").exitCode)
        assertEquals(Int.MIN_VALUE, ExecResult.decode("CATCH1|notanumber|a|b", "cmd").exitCode)
        assertEquals(Int.MIN_VALUE, ExecResult.decode("CATCH2|1|a|b", "cmd").exitCode)

        val error = ExecResult.decode(null, "am start")
        assertFalse(error.isSuccess)
        assertTrue(error.stderr.contains("empty response"))
    }

    @Test
    fun `error helper carries the message`() {
        val error = ExecResult.error("cmd", "shizuku unavailable")
        assertEquals(Int.MIN_VALUE, error.exitCode)
        assertEquals("shizuku unavailable", error.stderr)
        assertTrue(error.summary().contains("shizuku unavailable"))
    }

    @Test
    fun `summary is a single clipped line`() {
        val result = ExecResult("cmd", 0, "first line\nsecond line", "")
        assertEquals("exit=0 first line", result.summary())

        val long = ExecResult("cmd", 1, "y".repeat(1_000), "")
        assertTrue(long.summary(10).length < 40)
    }
}
