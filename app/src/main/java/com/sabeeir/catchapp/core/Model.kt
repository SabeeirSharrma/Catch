package com.sabeeir.catchapp.core

import kotlin.math.round

/** Whether the mirror window is expanded or collapsed to the bubble. */
enum class MirrorMode { SHOWN, HIDDEN }

/** Colour/meaning of the bubble. Priority order is defined by [BubbleStateReducer]. */
enum class BubbleState {
    /** Shizuku is not reachable: tapping and display control are dead. */
    SHIZUKU_DOWN,

    /** Roblox is not running on the virtual display any more. */
    ROBLOX_DEAD,

    /** The virtual display disappeared (release, reboot, OEM kill). */
    DISPLAY_LOST,

    /** A tap loop (auto-clicker or anti-idle) is currently firing. */
    LOOP_ON,

    /** Session healthy, mirror expanded. */
    RUNNING,

    /** Session healthy, collapsed to the bubble. */
    IDLE,
}

/** Why a session ended. Kept separate from UI strings so it can be tested. */
enum class StopReason {
    USER,
    SHIZUKU_DIED,
    ROBLOX_DIED,
    DISPLAY_LOST,
    CLICK_LIMIT,
    TIME_LIMIT,
    NOT_CALIBRATED,
    SERVICE_DESTROYED,
    PROJECTION_REVOKED,
}

/** Lifecycle of the tap loop. */
enum class LoopStatus { OFF, RUNNING, PAUSED, DONE }

/**
 * A saved tap target, in *virtual display* coordinates.
 */
data class ClickPoint(
    val id: String,
    val label: String,
    val x: Int,
    val y: Int,
) {
    init {
        require(id.isNotBlank()) { "point id must not be blank" }
        require(x >= 0 && y >= 0) { "point coordinates must be non-negative" }
    }
}

/**
 * Auto-clicker settings.
 *
 * [MAX_CPS] is a hard cap fixed in code (spec 9.1): the UI cannot raise it, and the
 * constructor refuses to build an out-of-range config at all.
 */
data class ClickerConfig(
    val cps: Float = DEFAULT_CPS,
    /** 0 means unlimited. */
    val maxClicks: Int = UNLIMITED,
    /** 0 means unlimited. */
    val maxMinutes: Int = UNLIMITED,
    val points: List<ClickPoint> = emptyList(),
    val activePointId: String? = null,
) {
    val activePoint: ClickPoint?
        get() = points.firstOrNull { it.id == activePointId } ?: points.firstOrNull()

    val clickIntervalMs: Long
        get() = round(1000.0 / cps).toLong().coerceAtLeast(MIN_INTERVAL_MS)

    init {
        require(cps.isFinite()) { "cps must be finite" }
        require(cps in MIN_CPS..MAX_CPS) { "cps must be in $MIN_CPS..$MAX_CPS" }
        require(maxClicks >= UNLIMITED) { "maxClicks must be >= 0" }
        require(maxMinutes >= UNLIMITED) { "maxMinutes must be >= 0" }
        require(points.size == points.distinctBy { it.id }.size) { "duplicate point ids" }
        require(activePointId == null || points.any { it.id == activePointId }) {
            "activePointId does not reference a saved point"
        }
    }

    companion object {
        const val MIN_CPS = 0.1f
        const val MAX_CPS = 30f
        const val DEFAULT_CPS = 5f
        const val UNLIMITED = 0
        const val MIN_INTERVAL_MS = 33L // 30 CPS ceiling, expressed as a floor on interval

        fun clampCps(value: Float): Float = value.coerceIn(MIN_CPS, MAX_CPS)
    }
}

/**
 * Anti-idle settings. Roblox kicks after ~20 minutes, so the interval is capped well
 * below that in code (spec 9.2).
 */
data class AntiIdleConfig(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_MINUTES,
) {
    init {
        require(intervalMinutes in 1..MAX_MINUTES) {
            "intervalMinutes must be in 1..$MAX_MINUTES (always well under the 20 minute kick)"
        }
    }

    val intervalMs: Long get() = intervalMinutes * 60_000L

    companion object {
        const val DEFAULT_MINUTES = 5
        const val MAX_MINUTES = 19
        fun clampMinutes(value: Int): Int = value.coerceIn(1, MAX_MINUTES)
    }
}

/** Raw signals the watchdog and UI consume. */
data class ServiceSignals(
    val shizukuAlive: Boolean,
    val robloxAlive: Boolean,
    val displayAlive: Boolean,
    val loopActive: Boolean,
    val mirrorShown: Boolean,
)

/** Which path the injector actually used for the last tap (diagnostics, spec 8/9.1). */
enum class InjectorPath { INPUT_MANAGER, SHELL_FALLBACK, UNAVAILABLE }
