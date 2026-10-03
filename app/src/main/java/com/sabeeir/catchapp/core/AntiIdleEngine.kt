package com.sabeeir.catchapp.core

/**
 * Anti-idle core (spec 9.2): a single tap every N minutes, always well under Roblox's
 * ~20 minute kick. Independent from the auto-clicker so it can run on its own.
 */
class AntiIdleEngine(val config: AntiIdleConfig, private val pointProvider: () -> ClickPoint?) {

    var status: LoopStatus = LoopStatus.OFF
        private set

    var tapsSent: Int = 0
        private set

    var stopReason: StopReason? = null
        private set

    private var startedAtMs: Long = 0
    private var nextDueAtMs: Long = 0
    private var stopEmitted = false

    val nextDueAt: Long get() = nextDueAtMs

    fun start(nowMs: Long): Boolean {
        if (pointProvider() == null) return false
        if (status == LoopStatus.RUNNING) return true
        status = LoopStatus.RUNNING
        stopReason = null
        tapsSent = 0
        startedAtMs = nowMs
        nextDueAtMs = nowMs + config.intervalMs
        return true
    }

    fun halt(reason: StopReason) {
        if (status == LoopStatus.DONE) return
        status = LoopStatus.DONE
        stopReason = reason
    }

    fun tick(nowMs: Long): List<LoopEvent> {
        when (status) {
            LoopStatus.OFF -> return emptyList()
            LoopStatus.DONE -> {
                val reason = stopReason ?: return emptyList()
                if (stopEmitted) return emptyList()
                stopEmitted = true
                return listOf(LoopEvent.Stopped(reason))
            }

            LoopStatus.PAUSED -> return emptyList()
            LoopStatus.RUNNING -> Unit
        }
        if (nowMs < nextDueAtMs) return emptyList()

        val point = pointProvider() ?: run {
            halt(StopReason.NOT_CALIBRATED)
            stopEmitted = true
            return listOf(LoopEvent.Stopped(StopReason.NOT_CALIBRATED))
        }

        tapsSent++
        nextDueAtMs = nowMs + config.intervalMs
        return listOf(LoopEvent.Click(point, tapsSent))
    }
}
