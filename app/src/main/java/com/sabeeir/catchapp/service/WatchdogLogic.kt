package com.sabeeir.catchapp.service

/**
 * Pure watchdog rules (spec sections 9.1, 11): decide what changed between two
 * observations. No Android types, so the transitions are unit tested directly.
 */
object WatchdogLogic {

    data class Snapshot(
        val shizukuAlive: Boolean,
        val robloxAlive: Boolean,
        val displayAlive: Boolean,
    ) {
        fun healthy(): Boolean = shizukuAlive && robloxAlive && displayAlive
    }

    enum class Alert {
        SHIZUKU_LOST,
        SHIZUKU_RESTORED,
        ROBLOX_DIED,
        ROBLOX_RESTORED,
        DISPLAY_LOST,
        DISPLAY_RESTORED,
    }

    private val HEALTHY = Snapshot(shizukuAlive = true, robloxAlive = true, displayAlive = true)

    /**
     * @param previous last observation, or null on the very first tick (a healthy
     *   baseline is assumed so that a broken start is still reported)
     */
    fun evaluate(previous: Snapshot?, current: Snapshot): List<Alert> {
        val before = previous ?: HEALTHY
        val alerts = mutableListOf<Alert>()
        if (before.shizukuAlive && !current.shizukuAlive) alerts += Alert.SHIZUKU_LOST
        if (!before.shizukuAlive && current.shizukuAlive) alerts += Alert.SHIZUKU_RESTORED
        if (before.robloxAlive && !current.robloxAlive) alerts += Alert.ROBLOX_DIED
        if (!before.robloxAlive && current.robloxAlive) alerts += Alert.ROBLOX_RESTORED
        if (before.displayAlive && !current.displayAlive) alerts += Alert.DISPLAY_LOST
        if (!before.displayAlive && current.displayAlive) alerts += Alert.DISPLAY_RESTORED
        return alerts
    }

    /** Alerts that must halt the tap loops immediately and for good. */
    fun stopsLoops(alert: Alert): Boolean = when (alert) {
        Alert.SHIZUKU_LOST, Alert.ROBLOX_DIED, Alert.DISPLAY_LOST -> true
        Alert.SHIZUKU_RESTORED, Alert.ROBLOX_RESTORED, Alert.DISPLAY_RESTORED -> false
    }

    fun describe(alert: Alert): String = when (alert) {
        Alert.SHIZUKU_LOST -> "Restart Shizuku"
        Alert.SHIZUKU_RESTORED -> "Shizuku reconnected"
        Alert.ROBLOX_DIED -> "Roblox stopped — tap to relaunch"
        Alert.ROBLOX_RESTORED -> "Roblox is back"
        Alert.DISPLAY_LOST -> "Virtual display lost"
        Alert.DISPLAY_RESTORED -> "Virtual display restored"
    }
}
