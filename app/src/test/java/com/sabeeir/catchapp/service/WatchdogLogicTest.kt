package com.sabeeir.catchapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogLogicTest {

    private val healthy = WatchdogLogic.Snapshot(
        shizukuAlive = true,
        robloxAlive = true,
        displayAlive = true,
    )

    @Test
    fun `stable state produces no alerts`() {
        assertTrue(WatchdogLogic.evaluate(healthy, healthy).isEmpty())
    }

    @Test
    fun `first observation assumes health so a broken start is still reported`() {
        val dead = healthy.copy(shizukuAlive = false)
        val alerts = WatchdogLogic.evaluate(null, dead)
        assertEquals(listOf(WatchdogLogic.Alert.SHIZUKU_LOST), alerts)
        assertTrue(WatchdogLogic.evaluate(null, healthy).isEmpty())
    }

    @Test
    fun `shizuku death and recovery are both reported`() {
        val dead = healthy.copy(shizukuAlive = false)
        assertEquals(
            listOf(WatchdogLogic.Alert.SHIZUKU_LOST),
            WatchdogLogic.evaluate(healthy, dead),
        )
        assertEquals(
            listOf(WatchdogLogic.Alert.SHIZUKU_RESTORED),
            WatchdogLogic.evaluate(dead, healthy),
        )
    }

    @Test
    fun `roblox death is detected by pid disappearing`() {
        val gone = healthy.copy(robloxAlive = false)
        assertEquals(
            listOf(WatchdogLogic.Alert.ROBLOX_DIED),
            WatchdogLogic.evaluate(healthy, gone),
        )
        assertEquals(
            listOf(WatchdogLogic.Alert.ROBLOX_RESTORED),
            WatchdogLogic.evaluate(gone, healthy),
        )
    }

    @Test
    fun `display loss is detected`() {
        val gone = healthy.copy(displayAlive = false)
        assertEquals(
            listOf(WatchdogLogic.Alert.DISPLAY_LOST),
            WatchdogLogic.evaluate(healthy, gone),
        )
    }

    @Test
    fun `multiple failures in one tick are all reported`() {
        val broken = WatchdogLogic.Snapshot(
            shizukuAlive = false,
            robloxAlive = false,
            displayAlive = false,
        )
        val alerts = WatchdogLogic.evaluate(healthy, broken)
        assertEquals(3, alerts.size)
    }

    @Test
    fun `only failures stop the loops - recoveries never restart them`() {
        for (alert in WatchdogLogic.Alert.values()) {
            val expected = alert in listOf(
                WatchdogLogic.Alert.SHIZUKU_LOST,
                WatchdogLogic.Alert.ROBLOX_DIED,
                WatchdogLogic.Alert.DISPLAY_LOST,
            )
            assertEquals(expected, WatchdogLogic.stopsLoops(alert))
        }
    }

    @Test
    fun `every alert can be shown to the user`() {
        for (alert in WatchdogLogic.Alert.values()) {
            assertTrue(WatchdogLogic.describe(alert).isNotBlank())
        }
        assertFalse(WatchdogLogic.describe(WatchdogLogic.Alert.SHIZUKU_LOST).isEmpty())
    }
}
