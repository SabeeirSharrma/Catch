package com.sabeeir.catchapp.core

/** Events emitted by the tap loops on each tick. */
sealed interface LoopEvent {
    /** One tap that must be injected at [point] on the virtual display. */
    data class Click(val point: ClickPoint, val index: Int) : LoopEvent

    /** The loop ended; nothing else will be sent until the user starts it again. */
    data class Stopped(val reason: StopReason) : LoopEvent
}
