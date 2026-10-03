package com.sabeeir.catchapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    @Test
    fun `launch targets the requested display and the stock Roblox package`() {
        val command = Commands.launchRoblox(42)
        assertTrue(command.contains("--display 42"))
        assertTrue(command.contains(Commands.ROBLOX_PACKAGE))
        assertTrue(command.startsWith("am start"))
        assertFalse(command.contains("com.roblox.client/")) // no hard-coded activity
    }

    @Test
    fun `launch refuses to target the default display`() {
        assertTrue(runCatching { Commands.launchRoblox(0) }.isFailure)
        assertTrue(runCatching { Commands.launchRoblox(-3) }.isFailure)
    }

    @Test
    fun `keep alive commands are exactly the ones from the spec`() {
        val commands = Commands.keepAliveCommands()
        assertEquals(4, commands.size)
        assertTrue(commands[0] == "dumpsys deviceidle whitelist +com.roblox.client")
        assertTrue(commands[1] == "appops set com.roblox.client RUN_ANY_IN_BACKGROUND allow")
        assertTrue(
            commands[2] == "settings put global settings_enable_monitor_phantom_procs false",
        )
        assertTrue(
            commands[3].startsWith("device_config put activity_manager max_phantom_processes"),
        )
    }

    @Test
    fun `force stop and pid are package scoped`() {
        assertEquals("am force-stop com.roblox.client", Commands.forceStopRoblox())
        assertEquals("pidof com.roblox.client", Commands.robloxPid())
        assertEquals("dumpsys activity activities", Commands.activitiesDump())
    }

    @Test
    fun `pidof output is parsed tolerantly`() {
        assertEquals(1234, Commands.parsePid("1234"))
        assertEquals(99, Commands.parsePid("  99   77\n"))
        assertNull(Commands.parsePid(""))
        assertNull(Commands.parsePid("\n"))
        assertNull(Commands.parsePid("not-a-pid"))
    }

    @Test
    fun `resumed detection only matches roblox rows`() {
        val dump = """
            Task{abc #1 type=STANDARD}
              * ActivityRecord{def u0 com.roblox.client/com.roblox.client.MainActivity t1}
                state=RESUMED
            Resumed activities:
              * ActivityRecord{123 u0 com.other.app/.MainActivity t2} state=PAUSED
        """.trimIndent()
        assertTrue(Commands.isResumed(dump))

        val paused = """
            * ActivityRecord{def u0 com.roblox.client/com.roblox.client.MainActivity t1}
              state=PAUSED
        """.trimIndent()
        assertFalse(Commands.isResumed(paused))

        val otherApp = """
            * ActivityRecord{def u0 com.other.app/.MainActivity t1}
              state=RESUMED
        """.trimIndent()
        assertFalse(Commands.isResumed(otherApp))
    }
}
