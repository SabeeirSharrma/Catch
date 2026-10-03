package com.sabeeir.catchapp.core.state

import com.sabeeir.catchapp.core.BubbleState
import com.sabeeir.catchapp.core.BubbleStateReducer
import com.sabeeir.catchapp.core.ClickPoint
import com.sabeeir.catchapp.core.LoopStatus
import com.sabeeir.catchapp.core.MirrorMode
import com.sabeeir.catchapp.core.ServiceSignals
import com.sabeeir.catchapp.core.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable session state shared by the service, the overlay and the UI.
 *
 * Everything the UI needs is a [StateFlow] so Activities can collect it with
 * `repeatOnLifecycle` without any event bus.
 */
class SessionStore {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _signals = MutableStateFlow(
        ServiceSignals(
            shizukuAlive = false,
            robloxAlive = false,
            displayAlive = false,
            loopActive = false,
            mirrorShown = false,
        ),
    )
    val signals: StateFlow<ServiceSignals> = _signals.asStateFlow()

    private val _bubbleState = MutableStateFlow(BubbleState.IDLE)
    val bubbleState: StateFlow<BubbleState> = _bubbleState.asStateFlow()

    private val _mirrorMode = MutableStateFlow(MirrorMode.HIDDEN)
    val mirrorMode: StateFlow<MirrorMode> = _mirrorMode.asStateFlow()

    private val _clickerStatus = MutableStateFlow(LoopStatus.OFF)
    val clickerStatus: StateFlow<LoopStatus> = _clickerStatus.asStateFlow()

    private val _antiIdleStatus = MutableStateFlow(LoopStatus.OFF)
    val antiIdleStatus: StateFlow<LoopStatus> = _antiIdleStatus.asStateFlow()

    private val _calibration = MutableStateFlow<ClickPoint?>(null)
    val calibration: StateFlow<ClickPoint?> = _calibration.asStateFlow()

    /** While true, taps on the mirror are captured as calibration instead of injected. */
    private val _calibrateMode = MutableStateFlow(false)
    val calibrateMode: StateFlow<Boolean> = _calibrateMode.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _lastStopReason = MutableStateFlow<StopReason?>(null)
    val lastStopReason: StateFlow<StopReason?> = _lastStopReason.asStateFlow()

    private val _statusDetail = MutableStateFlow("Grant the permissions below to start.")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _mirrorMode.value = MirrorMode.HIDDEN
            _clickerStatus.value = LoopStatus.OFF
            _antiIdleStatus.value = LoopStatus.OFF
            _calibrateMode.value = false
        }
        recompute()
    }

    fun updateSignals(
        shizukuAlive: Boolean? = null,
        robloxAlive: Boolean? = null,
        displayAlive: Boolean? = null,
        loopActive: Boolean? = null,
        mirrorShown: Boolean? = null,
    ) {
        val current = _signals.value
        _signals.value = current.copy(
            shizukuAlive = shizukuAlive ?: current.shizukuAlive,
            robloxAlive = robloxAlive ?: current.robloxAlive,
            displayAlive = displayAlive ?: current.displayAlive,
            loopActive = loopActive ?: current.loopActive,
            mirrorShown = mirrorShown ?: current.mirrorShown,
        )
        recompute()
    }

    fun setMirrorMode(mode: MirrorMode) {
        _mirrorMode.value = mode
        updateSignals(mirrorShown = mode == MirrorMode.SHOWN)
    }

    fun setClickerStatus(status: LoopStatus) {
        _clickerStatus.value = status
        recompute()
    }

    fun setAntiIdleStatus(status: LoopStatus) {
        _antiIdleStatus.value = status
        recompute()
    }

    fun setCalibration(point: ClickPoint?) {
        _calibration.value = point
    }

    fun setCalibrateMode(enabled: Boolean) {
        _calibrateMode.value = enabled
    }

    fun setDiagnostics(text: String) {
        _diagnostics.value = text
    }

    fun setLastStopReason(reason: StopReason?) {
        _lastStopReason.value = reason
    }

    fun setStatusDetail(text: String) {
        _statusDetail.value = text
    }

    private fun recompute() {
        val loopActive = _clickerStatus.value == LoopStatus.RUNNING ||
            _antiIdleStatus.value == LoopStatus.RUNNING
        updateSignals(loopActive = loopActive)
        _bubbleState.value = BubbleStateReducer.reduce(
            _signals.value.copy(loopActive = loopActive),
        )
    }

    /** True while either loop is doing something. */
    val isLooping: Boolean
        get() = _clickerStatus.value == LoopStatus.RUNNING ||
            _antiIdleStatus.value == LoopStatus.RUNNING
}
