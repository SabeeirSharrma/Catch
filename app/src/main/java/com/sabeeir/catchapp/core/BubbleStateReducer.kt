package com.sabeeir.catchapp.core

/**
 * Turns raw signals into the single state the bubble shows.
 *
 * Priority matters: infrastructure failures (Shizuku, Roblox, display) win over
 * cosmetic state, and the loop indicator wins over plain running/idle.
 */
object BubbleStateReducer {

    fun reduce(signals: ServiceSignals): BubbleState = when {
        !signals.shizukuAlive -> BubbleState.SHIZUKU_DOWN
        !signals.displayAlive -> BubbleState.DISPLAY_LOST
        !signals.robloxAlive -> BubbleState.ROBLOX_DEAD
        signals.loopActive -> BubbleState.LOOP_ON
        signals.mirrorShown -> BubbleState.RUNNING
        else -> BubbleState.IDLE
    }

    /** True when the session can still do useful work. */
    fun isHealthy(signals: ServiceSignals): Boolean =
        signals.shizukuAlive && signals.robloxAlive && signals.displayAlive

    /** Human readable status line, independent of Android resources. */
    fun describe(state: BubbleState): String = when (state) {
        BubbleState.SHIZUKU_DOWN -> "Restart Shizuku"
        BubbleState.ROBLOX_DEAD -> "Roblox is not running"
        BubbleState.DISPLAY_LOST -> "Virtual display lost"
        BubbleState.LOOP_ON -> "Loop active"
        BubbleState.RUNNING -> "Running (mirror shown)"
        BubbleState.IDLE -> "Running (hidden)"
    }
}
