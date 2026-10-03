package com.sabeeir.catchapp.core

/**
 * Auto-clicker core (spec 9.1).
 *
 * Pure and clock-injected: the service calls [tick] from its loop, tests drive it with
 * explicit timestamps. Rules enforced here, not by the UI:
 *  - fixed interval, no randomised timing;
 *  - rate is always inside [ClickerConfig.MIN_CPS]..[ClickerConfig.MAX_CPS];
 *  - optional click/minute limits stop the loop for good;
 *  - while paused (finger on the mirror) nothing is emitted and no backlog builds up;
 *  - once stopped (Stop, Shizuku death, display loss) it never restarts by itself.
 */
class ClickerEngine(private val config: ClickerConfig) {

    var status: LoopStatus = LoopStatus.OFF
        private set

    var clicksSent: Int = 0
        private set

    var stopReason: StopReason? = null
        private set

    private var startedAtMs: Long = 0
    private var lastResumeAtMs: Long = 0
    private var accumulatedActiveMs: Long = 0
    private var nextDueAtMs: Long = 0
    private var stopEmitted = false

    /** Visible for diagnostics: when the next click is due. */
    val nextDueAt: Long get() = nextDueAtMs

    val intervalMs: Long get() = config.clickIntervalMs

    /**
     * @return false when the loop cannot run (no calibration point yet, or the config
     * cannot produce a target). The engine stays [LoopStatus.OFF] in that case.
     */
    fun start(nowMs: Long): Boolean {
        if (config.activePoint == null) return false
        if (status == LoopStatus.RUNNING) return true
        status = LoopStatus.RUNNING
        stopReason = null
        clicksSent = 0
        startedAtMs = nowMs
        lastResumeAtMs = nowMs
        accumulatedActiveMs = 0
        nextDueAtMs = nowMs
        return true
    }

    fun pause(nowMs: Long) {
        if (status != LoopStatus.RUNNING) return
        accumulatedActiveMs += nowMs - lastResumeAtMs
        status = LoopStatus.PAUSED
    }

    fun resume(nowMs: Long) {
        if (status != LoopStatus.PAUSED) return
        status = LoopStatus.RUNNING
        lastResumeAtMs = nowMs
        // No catch-up burst: the first click after a pause is at most "immediately".
        if (nextDueAtMs < nowMs) nextDueAtMs = nowMs
    }

    /** Stops for good. Subsequent ticks emit nothing. */
    fun halt(reason: StopReason, nowMs: Long = startedAtMs) {
        if (status == LoopStatus.DONE) return
        if (status == LoopStatus.RUNNING) accumulatedActiveMs += nowMs - lastResumeAtMs
        status = LoopStatus.DONE
        stopReason = reason
    }

    /** @return events to deliver now; empty when nothing is due. */
    fun tick(nowMs: Long): List<LoopEvent> {
        when (status) {
            LoopStatus.OFF -> return emptyList()
            LoopStatus.DONE -> return emitStopOnce()
            LoopStatus.PAUSED -> return emptyList()
            LoopStatus.RUNNING -> Unit
        }

        val elapsedActive = accumulatedActiveMs + (nowMs - lastResumeAtMs)
        if (config.maxMinutes > 0 && elapsedActive >= config.maxMinutes * 60_000L) {
            halt(StopReason.TIME_LIMIT, nowMs)
            return emitStop(StopReason.TIME_LIMIT)
        }

        if (nowMs < nextDueAtMs) return emptyList()

        val point = config.activePoint ?: run {
            halt(StopReason.NOT_CALIBRATED, nowMs)
            return emitStop(StopReason.NOT_CALIBRATED)
        }

        // Drop absurd backlog (device slept, process stalled) instead of bursting.
        if (nowMs - nextDueAtMs > intervalMs * MAX_BACKLOG_TICKS) {
            nextDueAtMs = nowMs
        }

        clicksSent++
        nextDueAtMs = nowMs + intervalMs

        val events = mutableListOf<LoopEvent>(LoopEvent.Click(point, clicksSent))
        if (config.maxClicks > 0 && clicksSent >= config.maxClicks) {
            halt(StopReason.CLICK_LIMIT, nowMs)
            events += emitStop(StopReason.CLICK_LIMIT)
        }
        return events
    }

    companion object {
        /** Ticks of backlog tolerated before the schedule is resynchronised. */
        const val MAX_BACKLOG_TICKS = 3
    }

    /** The stop reason is reported exactly once, so the UI never sees a loop of alerts. */
    private fun emitStopOnce(): List<LoopEvent> {
        val reason = stopReason ?: return emptyList()
        if (stopEmitted) return emptyList()
        stopEmitted = true
        return listOf(LoopEvent.Stopped(reason))
    }

    private fun emitStop(reason: StopReason): List<LoopEvent> {
        if (stopEmitted) return emptyList()
        stopEmitted = true
        return listOf(LoopEvent.Stopped(reason))
    }
}
