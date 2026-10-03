package com.sabeeir.catchapp.shell

/**
 * Every privileged command Catch ever runs, as plain strings.
 *
 * Keeping them in one pure object means the exact text is unit tested, and the app can
 * show the user precisely what will be executed.
 */
object Commands {

    const val ROBLOX_PACKAGE = "com.roblox.client"

    /** Launch the stock Play Store Roblox onto a specific display (spec section 4/7). */
    fun launchRoblox(displayId: Int): String {
        require(displayId > 0) { "refusing to launch onto display $displayId" }
        return "am start --display $displayId " +
            "-a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER " +
            "-p $ROBLOX_PACKAGE"
    }

    fun forceStopRoblox(): String = "am force-stop $ROBLOX_PACKAGE"

    fun robloxPid(): String = "pidof $ROBLOX_PACKAGE"

    /** M0 check 6 helper: is Roblox RESUMED on its display? */
    fun activitiesDump(): String = "dumpsys activity activities"

    /**
     * One-time keep-alive tuning (spec section 10).
     *
     * `max_phantom_processes` is raised instead of lowered: the phantom process killer
     * enforces it as a ceiling, so a very large value disables the kill for our session.
     */
    fun keepAliveCommands(): List<String> = listOf(
        "dumpsys deviceidle whitelist +$ROBLOX_PACKAGE",
        "appops set $ROBLOX_PACKAGE RUN_ANY_IN_BACKGROUND allow",
        "settings put global settings_enable_monitor_phantom_procs false",
        "device_config put activity_manager max_phantom_processes 2147483647",
    )

    /** Extracts the first pid from a `pidof` response, or null. */
    fun parsePid(pidofOutput: String): Int? =
        pidofOutput.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /**
     * Crude but honest check for M0 check 6: does the activity dump list Roblox as
     * RESUMED? Looks at the lines that mention the Roblox package and the two lines
     * after each of them, which is where `state=` appears in `dumpsys activity`.
     */
    fun isResumed(activityDump: String): Boolean {
        val lines = activityDump.lines()
        for (index in lines.indices) {
            if (!lines[index].contains(ROBLOX_PACKAGE)) continue
            for (line in index until minOf(index + LOOKAHEAD, lines.size)) {
                if (lines[line].contains("RESUMED", ignoreCase = true)) return true
            }
        }
        return false
    }

    /** Lines inspected after each package match. */
    private const val LOOKAHEAD = 3
}
