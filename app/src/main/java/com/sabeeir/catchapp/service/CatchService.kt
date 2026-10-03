package com.sabeeir.catchapp.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sabeeir.catchapp.CatchApplication
import com.sabeeir.catchapp.core.AntiIdleConfig
import com.sabeeir.catchapp.core.AntiIdleEngine
import com.sabeeir.catchapp.core.ClickPoint
import com.sabeeir.catchapp.core.ClickerConfig
import com.sabeeir.catchapp.core.ClickerEngine
import com.sabeeir.catchapp.core.InjectorGuard
import com.sabeeir.catchapp.core.LoopEvent
import com.sabeeir.catchapp.core.LoopStatus
import com.sabeeir.catchapp.core.MirrorMode
import com.sabeeir.catchapp.core.StopReason
import com.sabeeir.catchapp.core.StopSequence
import com.sabeeir.catchapp.display.DisplayConfig
import com.sabeeir.catchapp.display.MirrorSink
import com.sabeeir.catchapp.display.VirtualDisplayManager
import com.sabeeir.catchapp.overlay.BubbleOverlay
import com.sabeeir.catchapp.overlay.GestureAction
import com.sabeeir.catchapp.overlay.MenuAction
import com.sabeeir.catchapp.shell.Commands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground service from spec section 4: it owns the virtual display, the bubble
 * and the tap loop, and it is the only component allowed to tear them down.
 *
 * Everything privileged goes through [com.sabeeir.catchapp.shell.ShizukuSession]; the
 * service never assumes root and degrades (with a notification) when Shizuku dies.
 */
class CatchService : LifecycleService() {

    private lateinit var app: CatchApplication
    private lateinit var notifications: NotificationFactory

    private val guard = InjectorGuard()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var displayManager: VirtualDisplayManager? = null
    private var sink: MirrorSink? = null
    private var projection: MediaProjection? = null
    private var overlay: BubbleOverlay? = null

    private var clicker: ClickerEngine? = null
    private var antiIdle: AntiIdleEngine? = null

    private var loopJob: Job? = null
    private var watchdogJob: Job? = null
    private var shizukuJob: Job? = null

    private var previousSnapshot: WatchdogLogic.Snapshot? = null
    private var displayConfig = DisplayConfig()
    private var displayId: Int = NO_DISPLAY
    private var sessionActive = false
    private var foregroundStarted = false
    private var stopping = false

    // ------------------------------------------------------------------ life cycle

    override fun onCreate() {
        super.onCreate()
        app = CatchApplication.from(this)
        notifications = NotificationFactory(this).also { it.ensureChannel() }

        shizukuJob = lifecycleScope.launch {
            app.shizuku.state.collect { state ->
                val alive = state == com.sabeeir.catchapp.shell.ShizukuSession.BinderState.AVAILABLE
                if (alive) guard.shizukuRestored() else guard.shizukuLost()
                app.store.updateSignals(shizukuAlive = alive)
                if (!alive && sessionActive) {
                    haltLoops(StopReason.SHIZUKU_DIED)
                }
                publishNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()

        when (intent?.action) {
            CatchActions.ACTION_START -> startSession(intent)
            CatchActions.ACTION_SHOW -> setMirrorMode(MirrorMode.SHOWN)
            CatchActions.ACTION_HIDE -> setMirrorMode(MirrorMode.HIDDEN)
            CatchActions.ACTION_TOGGLE_LOOP -> toggleClicker()
            CatchActions.ACTION_STOP_LOOP -> haltLoops(StopReason.USER)
            CatchActions.ACTION_APPLY_TOOLS -> applyTools(intent)
            CatchActions.ACTION_RELAUNCH -> relaunchRoblox()
            CatchActions.ACTION_STOP_ALL -> lifecycleScope.launch { stopAll(StopReason.USER) }
            else -> if (!sessionActive) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        loopJob?.cancel()
        watchdogJob?.cancel()
        shizukuJob?.cancel()
        releaseSession()
        app.store.setServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ------------------------------------------------------------------ start

    private fun startSession(intent: Intent) {
        if (sessionActive) {
            publishNotification()
            return
        }

        val resultCode = intent.getIntExtra(CatchActions.EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val consent: Intent? = intent.getParcelableExtra(CatchActions.EXTRA_RESULT_DATA)
        val width = intent.getIntExtra(CatchActions.EXTRA_WIDTH, DisplayConfig.DEFAULT_WIDTH)
        val height = intent.getIntExtra(CatchActions.EXTRA_HEIGHT, DisplayConfig.DEFAULT_HEIGHT)
        val dpi = intent.getIntExtra(CatchActions.EXTRA_DPI, DisplayConfig.DEFAULT_DPI)

        if (resultCode == Int.MIN_VALUE || consent == null) {
            failAndStop("Screen capture consent is missing")
            return
        }

        displayConfig = DisplayConfig.sanitized(width, height, dpi)

        // Android 14+: a mediaProjection typed FGS must be running *before*
        // getMediaProjection().
        startForegroundCompat(withProjection = true)

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = runCatching { manager.getMediaProjection(resultCode, consent) }
            .onFailure { Log.w(TAG, "getMediaProjection failed: $it") }
            .getOrNull()
        if (projection == null) {
            failAndStop("Could not obtain a media projection")
            return
        }

        val newSink = MirrorSink(displayConfig)
        sink = newSink

        val newDisplayManager = VirtualDisplayManager(
            getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager,
            onProjectionStopped = {
                mainHandler.post {
                    if (sessionActive) lifecycleScope.launch { stopAll(StopReason.PROJECTION_REVOKED) }
                }
            },
        )
        displayManager = newDisplayManager

        val newDisplayId = newDisplayManager.create(projection!!, displayConfig, newSink.surface)
        if (newDisplayId == null || newDisplayId <= 0) {
            failAndStop("Virtual display creation failed: ${newDisplayManager.lastError}")
            return
        }
        displayId = newDisplayId
        guard.sessionStarted(displayId)
        guard.shizukuAlive = app.shizuku.isBinderAlive
        app.store.updateSignals(displayAlive = true, shizukuAlive = guard.shizukuAlive)

        sessionActive = true
        previousSnapshot = null

        ensureEngines()
        attachOverlay()
        startLoop()
        startWatchdog()

        val point = app.store.calibration.value ?: app.settings.calibrationPoint
        if (point == null) {
            app.store.setStatusDetail("Started. Set a calibration point to enable the loop.")
        } else {
            app.store.setStatusDetail("Running. Roblox is on the hidden display.")
        }

        lifecycleScope.launch {
            val result = app.shizuku.exec(Commands.launchRoblox(displayId))
            if (!result.isSuccess) {
                app.store.setStatusDetail("Launch failed: ${result.summary()}")
            }
            app.store.updateSignals(robloxAlive = true)
            publishNotification()
        }

        app.store.setServiceRunning(true)
        publishNotification()
    }

    private fun attachOverlay() {
        overlay?.close()
        overlay = BubbleOverlay(this, app.settings, overlayListener).also { it.show() }
        app.store.setMirrorMode(MirrorMode.HIDDEN)
    }

    private fun failAndStop(message: String) {
        Log.w(TAG, "start failed: $message")
        app.store.setStatusDetail(message)
        lifecycleScope.launch { stopAll(StopReason.USER) }
    }

    // ------------------------------------------------------------------ mirror

    private fun setMirrorMode(mode: MirrorMode) {
        if (!sessionActive) return
        overlay?.setExpanded(mode == MirrorMode.SHOWN)
        if (mode == MirrorMode.HIDDEN) {
            sink?.let { displayManager?.routeTo(it.surface) }
        }
        app.store.setMirrorMode(mode)
        publishNotification()
    }

    private fun routeSurface(surface: android.view.Surface?) {
        if (!sessionActive) return
        if (surface == null) {
            sink?.let { displayManager?.routeTo(it.surface) }
        } else {
            displayManager?.routeTo(surface)
        }
    }

    // ------------------------------------------------------------------ tools

    private fun ensureEngines() {
        val point = app.store.calibration.value ?: app.settings.calibrationPoint
        if (point != null && app.store.calibration.value == null) {
            app.store.setCalibration(point)
        }

        val wasRunning = clicker?.status == LoopStatus.RUNNING
        val config = ClickerConfig(
            cps = app.settings.cps,
            maxClicks = app.settings.maxClicks,
            maxMinutes = app.settings.maxMinutes,
            points = listOfNotNull(point),
            activePointId = point?.id,
        )
        clicker = ClickerEngine(config)
        if (wasRunning) clicker?.start(SystemClock.uptimeMillis())

        val antiIdleWasRunning = antiIdle?.status == LoopStatus.RUNNING
        antiIdle = AntiIdleEngine(
            AntiIdleConfig(
                enabled = true,
                intervalMinutes = app.settings.antiIdleMinutes,
            ),
        ) { app.store.calibration.value ?: app.settings.calibrationPoint }
        if (antiIdleWasRunning) antiIdle?.start(SystemClock.uptimeMillis())

        syncLoopStatuses()
    }

    private fun applyTools(intent: Intent) {
        app.settings.cps = intent.getFloatExtra(CatchActions.EXTRA_CPS, app.settings.cps)
        app.settings.maxClicks = intent.getIntExtra(CatchActions.EXTRA_MAX_CLICKS, app.settings.maxClicks)
        app.settings.maxMinutes = intent.getIntExtra(CatchActions.EXTRA_MAX_MINUTES, app.settings.maxMinutes)
        app.settings.antiIdleMinutes =
            intent.getIntExtra(CatchActions.EXTRA_ANTI_IDLE_MINUTES, app.settings.antiIdleMinutes)

        ensureEngines()

        val antiIdleOn = intent.getBooleanExtra(CatchActions.EXTRA_ANTI_IDLE, false)
        if (antiIdleOn) {
            if (antiIdle?.status != LoopStatus.RUNNING) {
                if (antiIdle?.start(SystemClock.uptimeMillis()) == false) {
                    app.store.setStatusDetail("Anti-idle needs a calibration point")
                }
            }
        } else if (antiIdle?.status == LoopStatus.RUNNING) {
            antiIdle?.halt(StopReason.USER)
        }
        syncLoopStatuses()
        publishNotification()
    }

    private fun toggleClicker() {
        val now = SystemClock.uptimeMillis()
        if (clicker == null) ensureEngines()
        val target = clicker ?: return
        if (target.status == LoopStatus.RUNNING) {
            target.halt(StopReason.USER, now)
        } else {
            val started = target.start(now)
            if (!started) {
                app.store.setStatusDetail("Set a calibration point first (tap the mirror)")
                app.store.setCalibrateMode(true)
                overlay?.setExpanded(true)
            }
        }
        syncLoopStatuses()
        publishNotification()
    }

    private fun syncLoopStatuses() {
        app.store.setClickerStatus(clicker?.status ?: LoopStatus.OFF)
        app.store.setAntiIdleStatus(antiIdle?.status ?: LoopStatus.OFF)
    }

    private fun haltLoops(reason: StopReason) {
        val now = SystemClock.uptimeMillis()
        clicker?.halt(reason, now)
        antiIdle?.halt(reason)
        syncLoopStatuses()
        if (reason != StopReason.USER) {
            app.store.setLastStopReason(reason)
            app.store.setStatusDetail(WatchdogLogic.describe(alertFor(reason)))
        }
        publishNotification()
    }

    private fun alertFor(reason: StopReason) = when (reason) {
        StopReason.SHIZUKU_DIED -> WatchdogLogic.Alert.SHIZUKU_LOST
        StopReason.ROBLOX_DIED -> WatchdogLogic.Alert.ROBLOX_DIED
        StopReason.DISPLAY_LOST -> WatchdogLogic.Alert.DISPLAY_LOST
        else -> WatchdogLogic.Alert.SHIZUKU_RESTORED
    }

    // ------------------------------------------------------------------ loops

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = lifecycleScope.launch {
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val events = mutableListOf<LoopEvent>()
                clicker?.tick(now)?.let { events += it }
                antiIdle?.tick(now)?.let { events += it }
                for (event in events) handleLoopEvent(event)
                delay(nextDelay(now))
            }
        }
    }

    private fun nextDelay(nowMs: Long): Long {
        val due = listOfNotNull(
            clicker?.takeIf { it.status == LoopStatus.RUNNING }?.nextDueAt,
            antiIdle?.takeIf { it.status == LoopStatus.RUNNING }?.nextDueAt,
        )
        if (due.isEmpty()) return IDLE_POLL_MS
        val wait = (due.min() - nowMs).coerceIn(MIN_TICK_MS, MAX_TICK_MS)
        return wait
    }

    private suspend fun handleLoopEvent(event: LoopEvent) {
        when (event) {
            is LoopEvent.Click -> {
                val refusal = guard.check(displayId)
                if (refusal != InjectorGuard.Refusal.OK) {
                    haltLoops(reasonFor(refusal))
                    return
                }
                val code = app.shizuku.tap(event.point.x, event.point.y, displayId)
                if (code < 0) {
                    app.store.setDiagnostics("tap refused: code=$code")
                    haltLoops(reasonFor(guard.check(displayId)))
                }
            }

            is LoopEvent.Stopped -> {
                app.store.setLastStopReason(event.reason)
                app.store.setStatusDetail("Loop stopped (${event.reason})")
                syncLoopStatuses()
                publishNotification()
            }
        }
    }

    private fun reasonFor(refusal: InjectorGuard.Refusal): StopReason = when (refusal) {
        InjectorGuard.Refusal.SHIZUKU_DEAD -> StopReason.SHIZUKU_DIED
        InjectorGuard.Refusal.DISPLAY_GONE,
        InjectorGuard.Refusal.NO_DISPLAY,
        InjectorGuard.Refusal.ID_MISMATCH,
        -> StopReason.DISPLAY_LOST
        InjectorGuard.Refusal.STOPPED -> StopReason.USER
        InjectorGuard.Refusal.OK -> StopReason.USER
    }

    // ------------------------------------------------------------------ watchdog

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val snapshot = WatchdogLogic.Snapshot(
                    shizukuAlive = app.shizuku.isBinderAlive,
                    robloxAlive = probeRobloxAlive(),
                    displayAlive = displayManager?.isDisplayAlive() ?: false,
                )
                val alerts = WatchdogLogic.evaluate(previousSnapshot, snapshot)
                previousSnapshot = snapshot
                app.store.updateSignals(
                    shizukuAlive = snapshot.shizukuAlive,
                    robloxAlive = snapshot.robloxAlive,
                    displayAlive = snapshot.displayAlive,
                )
                for (alert in alerts) handleAlert(alert)
                publishNotification()
            }
        }
    }

    private suspend fun probeRobloxAlive(): Boolean {
        if (!app.shizuku.isBinderAlive) {
            // Unknown: never turn a dead Shizuku into a false "Roblox died".
            return app.store.signals.value.robloxAlive
        }
        val result = app.shizuku.exec(Commands.robloxPid(), timeoutMs = 5_000)
        return if (result.isSuccess) {
            Commands.parsePid(result.stdout) != null
        } else {
            app.store.signals.value.robloxAlive
        }
    }

    private fun handleAlert(alert: WatchdogLogic.Alert) {
        Log.i(TAG, "watchdog: $alert")
        if (WatchdogLogic.stopsLoops(alert)) {
            haltLoops(
                when (alert) {
                    WatchdogLogic.Alert.SHIZUKU_LOST -> StopReason.SHIZUKU_DIED
                    WatchdogLogic.Alert.ROBLOX_DIED -> StopReason.ROBLOX_DIED
                    else -> StopReason.DISPLAY_LOST
                },
            )
        }
        when (alert) {
            WatchdogLogic.Alert.SHIZUKU_LOST -> {
                guard.shizukuLost()
                app.store.setStatusDetail(WatchdogLogic.describe(alert))
            }

            WatchdogLogic.Alert.SHIZUKU_RESTORED -> {
                guard.shizukuRestored()
                app.store.setStatusDetail(WatchdogLogic.describe(alert))
            }

            WatchdogLogic.Alert.DISPLAY_LOST -> {
                guard.displayLost()
                app.store.setStatusDetail(WatchdogLogic.describe(alert))
            }

            WatchdogLogic.Alert.ROBLOX_DIED,
            WatchdogLogic.Alert.ROBLOX_RESTORED,
            WatchdogLogic.Alert.DISPLAY_RESTORED,
            -> app.store.setStatusDetail(WatchdogLogic.describe(alert))
        }
    }

    private fun relaunchRoblox() {
        if (displayId <= 0) return
        lifecycleScope.launch {
            val result = app.shizuku.exec(Commands.launchRoblox(displayId))
            app.store.setStatusDetail(
                if (result.isSuccess) "Roblox relaunched" else "Relaunch failed: ${result.summary()}",
            )
            app.store.updateSignals(robloxAlive = result.isSuccess)
            publishNotification()
        }
    }

    // ------------------------------------------------------------------ stop

    private suspend fun stopAll(reason: StopReason) {
        if (stopping) return
        stopping = true
        app.store.setLastStopReason(reason)

        val failures = StopSequence { step ->
            when (step) {
                StopSequence.Step.HALT_TOOLS -> {
                    loopJob?.cancel()
                    watchdogJob?.cancel()
                    val now = SystemClock.uptimeMillis()
                    clicker?.halt(reason, now)
                    antiIdle?.halt(reason)
                    syncLoopStatuses()
                }

                StopSequence.Step.FORCE_STOP_ROBLOX ->
                    if (app.shizuku.isBinderAlive) {
                        app.shizuku.exec(Commands.forceStopRoblox())
                    }

                StopSequence.Step.RELEASE_DISPLAY -> releaseSession()

                StopSequence.Step.STOP_SERVICE -> {
                    sessionActive = false
                    stopping = false
                    app.store.setServiceRunning(false)
                    app.store.setStatusDetail("Stopped")
                    notifications.cancel()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.run()

        if (failures.isNotEmpty()) {
            Log.w(TAG, "stop failures: $failures")
            app.store.setDiagnostics(failures.joinToString("\n") { "${it.step}: ${it.message}" })
        }
    }

    /** Order matters: bubble first, then the sink, then the display itself. */
    private fun releaseSession() {
        overlay?.close()
        overlay = null
        displayManager?.release()
        displayManager = null
        sink?.close()
        sink = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        displayId = NO_DISPLAY
        guard.sessionStopped()
        previousSnapshot = null
        app.store.updateSignals(displayAlive = false, robloxAlive = false, mirrorShown = false)
        app.store.setMirrorMode(MirrorMode.HIDDEN)
    }

    // ------------------------------------------------------------------ overlay glue

    private val overlayListener = object : BubbleOverlay.Listener {
        override fun displaySize(): Pair<Int, Int> = displayConfig.width to displayConfig.height

        override fun calibrateMode(): Boolean = app.store.calibrateMode.value

        override fun onMirrorGesture(action: GestureAction, x: Int, y: Int, isTap: Boolean) {
            lifecycleScope.launch { handleGesture(action, x, y, isTap) }
        }

        override fun onMirrorTouchStart() {
            clicker?.pause(SystemClock.uptimeMillis())
        }

        override fun onMirrorTouchEnd() {
            clicker?.resume(SystemClock.uptimeMillis())
        }

        override fun onMirrorSurface(surface: android.view.Surface?) = routeSurface(surface)

        override fun onMenuAction(action: MenuAction) {
            when (action) {
                MenuAction.TOGGLE_MIRROR ->
                    setMirrorMode(
                        if (app.store.mirrorMode.value == MirrorMode.SHOWN) MirrorMode.HIDDEN else MirrorMode.SHOWN,
                    )

                MenuAction.HIDE -> setMirrorMode(MirrorMode.HIDDEN)
                MenuAction.TOGGLE_LOOP -> toggleClicker()
                MenuAction.STOP -> lifecycleScope.launch { stopAll(StopReason.USER) }
            }
        }
    }

    private suspend fun handleGesture(action: GestureAction, x: Int, y: Int, isTap: Boolean) {
        if (app.store.calibrateMode.value) {
            if (action == GestureAction.UP && isTap) {
                val point = ClickPoint(id = POINT_ID, label = "Point 1", x = x, y = y)
                app.settings.calibrationPoint = point
                app.store.setCalibration(point)
                app.store.setCalibrateMode(false)
                ensureEngines()
                app.store.setStatusDetail("Calibrated at ($x, $y)")
            }
            return
        }

        if (!guard.canInject(displayId)) return

        val pointerAction = when (action) {
            GestureAction.DOWN -> com.sabeeir.catchapp.shell.ShizukuSession.PointerAction.DOWN
            GestureAction.MOVE -> com.sabeeir.catchapp.shell.ShizukuSession.PointerAction.MOVE
            GestureAction.UP -> com.sabeeir.catchapp.shell.ShizukuSession.PointerAction.UP
        }
        val code = app.shizuku.pointer(pointerAction, x, y, displayId)
        if (code < 0) {
            app.store.setDiagnostics("gesture refused: code=$code")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun startForegroundCompat(withProjection: Boolean = foregroundStarted) {
        val state = currentState()
        val notification = notifications.build(state)
        if (Build.VERSION.SDK_INT >= 34) {
            val types = if (withProjection || projection != null) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            ServiceCompat.startForeground(this, NotificationFactory.NOTIFICATION_ID, notification, types)
        } else {
            // Pre-14: the two-arg form uses the manifest types, which is what the
            // platform expects for mediaProjection before/after consent.
            startForeground(NotificationFactory.NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun currentState() = NotificationState(
        bubbleState = app.store.bubbleState.value,
        mirrorShown = app.store.mirrorMode.value == MirrorMode.SHOWN,
        loopActive = app.store.isLooping,
        robloxAlive = app.store.signals.value.robloxAlive,
        displayAlive = app.store.signals.value.displayAlive,
    )

    private fun publishNotification() {
        if (!foregroundStarted) return
        val state = currentState()
        notifications.publish(state)
        overlay?.updateState(state.bubbleState, state.loopActive)
    }

    companion object {
        private const val TAG = "CatchService"
        private const val NO_DISPLAY = -1
        private const val POINT_ID = "active"

        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val IDLE_POLL_MS = 500L
        private const val MIN_TICK_MS = 10L
        private const val MAX_TICK_MS = 250L
    }
}
