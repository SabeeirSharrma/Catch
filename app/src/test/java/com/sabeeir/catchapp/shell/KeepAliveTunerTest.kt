package com.sabeeir.catchapp.shell

import com.sabeeir.catchapp.core.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveTunerTest {

    private fun ok(command: String) = ExecResult(command, 0, "", "")
    private fun fail(command: String) = ExecResult(command, 255, "", "Permission denied")

    @Test
    fun `applies every command in order and records the outcome`() = runBlocking {
        val seen = mutableListOf<String>()
        val results = KeepAliveTuner { command ->
            seen += command
            ok(command)
        }.apply()

        assertEquals(Commands.keepAliveCommands(), seen)
        assertEquals(4, results.size)
        assertTrue(results.all { it.ok })
    }

    @Test
    fun `one rejected command does not abort the rest`() = runBlocking {
        val results = KeepAliveTuner { command ->
            if (command.startsWith("settings put")) fail(command) else ok(command)
        }.apply()

        assertEquals(4, results.size)
        assertEquals(1, results.count { !it.ok })
        assertTrue(results.first { !it.ok }.command.startsWith("settings put"))
        assertTrue(results.count { it.ok } == 3)
    }

    @Test
    fun `a throwing executor is converted into a failure result`() = runBlocking {
        val results = KeepAliveTuner { throw IllegalStateException("shizuku died") }.apply()

        assertEquals(4, results.size)
        assertFalse(results.any { it.ok })
        assertTrue(results.all { it.detail.contains("shizuku died") })
    }

    @Test
    fun `stderr exceptions are not reported as success`() = runBlocking {
        val results = KeepAliveTuner { command ->
            ExecResult(command, 0, "", "java.lang.SecurityException: nope")
        }.apply()

        assertTrue(results.none { it.ok })
    }
}
