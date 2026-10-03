package com.sabeeir.catchapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sabeeir.catchapp.CatchApplication
import com.sabeeir.catchapp.core.BubbleStateReducer
import com.sabeeir.catchapp.core.ClickPoint
import com.sabeeir.catchapp.core.LoopStatus
import com.sabeeir.catchapp.core.MirrorMode
import com.sabeeir.catchapp.display.DisplayConfig
import com.sabeeir.catchapp.databinding.ActivityMainBinding
import com.sabeeir.catchapp.service.CatchActions
import com.sabeeir.catchapp.service.CatchService
import com.sabeeir.catchapp.shell.KeepAliveTuner
import com.sabeeir.catchapp.shell.SelfTestReport
import com.sabeeir.catchapp.shell.ShizukuSession
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: CatchApplication

    private val projectionManager
        get() = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startSession(result.resultCode, data)
            } else {
                app.store.setStatusDetail("Screen capture was denied")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionRows()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = CatchApplication.from(this)
        app.shizuku.permissionListenerCallback = { runOnUiThread { refreshPermissionRows() } }

        wireButtons()
        observeState()
        refreshPermissionRows()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
    }

    override fun onDestroy() {
        if (isFinishing) app.shizuku.permissionListenerCallback = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ wiring

    private fun wireButtons() {
        binding.shizukuButton.setOnClickListener {
            if (shizukuGranted()) refreshPermissionRows() else app.shizuku.requestPermission(ShizukuSession.PERMISSION_REQUEST_CODE)
        }

        binding.overlayButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        binding.notificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                refreshPermissionRows()
            }
        }

        binding.startStopButton.setOnClickListener {
            if (app.store.serviceRunning.value) {
                sendToService(CatchActions.ACTION_STOP_ALL)
            } else {
                requestProjection()
            }
        }

        binding.loopSwitch.setOnCheckedChangeListener { _, checked ->
            app.settings.cps = binding.cpsSlider.value
            if (app.store.serviceRunning.value) {
                sendTools()
                if (checked) sendToService(CatchActions.ACTION_TOGGLE_LOOP)
            }
        }

        binding.antiIdleSwitch.setOnCheckedChangeListener { _, checked ->
            app.settings.antiIdleMinutes = binding.antiIdleSlider.value.toInt()
            if (app.store.serviceRunning.value) sendTools()
        }

        binding.cpsSlider.addOnChangeListener { _, value, fromUser ->
            binding.cpsValue.text = getString(com.sabeeir.catchapp.R.string.label_cps) +
                " — %.1f CPS".format(value)
            if (fromUser) app.settings.cps = value
        }

        binding.antiIdleSlider.addOnChangeListener { _, value, fromUser ->
            binding.antiIdleValue.text =
                getString(com.sabeeir.catchapp.R.string.label_anti_idle_minutes) +
                    " — ${value.toInt()} min"
            if (fromUser) app.settings.antiIdleMinutes = value.toInt()
        }

        binding.calibrateButton.setOnClickListener {
            app.store.setCalibrateMode(true)
            if (app.store.serviceRunning.value) {
                sendToService(CatchActions.ACTION_SHOW)
            } else {
                app.store.setStatusDetail("Start a session first, then tap the point in the mirror")
            }
        }

        binding.selfTestButton.setOnClickListener { runSelfTest() }
        binding.keepAliveButton.setOnClickListener { runKeepAlive() }

        // Restore persisted values.
        binding.cpsSlider.value = app.settings.cps
        binding.antiIdleSlider.value = app.settings.antiIdleMinutes.toFloat()
        binding.cpsValue.text =
            getString(com.sabeeir.catchapp.R.string.label_cps) + " — %.1f CPS".format(app.settings.cps)
        binding.antiIdleValue.text =
            getString(com.sabeeir.catchapp.R.string.label_anti_idle_minutes) +
                " — ${app.settings.antiIdleMinutes} min"
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.store.bubbleState.collect { state ->
                        binding.statusText.text = BubbleStateReducer.describe(state)
                    }
                }
                launch {
                    app.store.statusDetail.collect { detail ->
                        binding.statusDetail.text = detail
                    }
                }
                launch {
                    app.store.serviceRunning.collect { running ->
                        binding.startStopButton.setText(
                            if (running) com.sabeeir.catchapp.R.string.btn_stop
                            else com.sabeeir.catchapp.R.string.btn_start,
                        )
                        if (!running) {
                            binding.loopSwitch.isChecked = false
                            binding.antiIdleSwitch.isChecked = false
                        }
                    }
                }
                launch {
                    app.store.clickerStatus.collect { status ->
                        binding.loopSwitch.isChecked = status == LoopStatus.RUNNING
                    }
                }
                launch {
                    app.store.antiIdleStatus.collect { status ->
                        binding.antiIdleSwitch.isChecked = status == LoopStatus.RUNNING
                    }
                }
                launch {
                    app.store.calibration.collect { point -> renderCalibration(point) }
                }
                launch {
                    app.store.diagnostics.collect { text -> binding.diagnosticOutput.text = text }
                }
                launch {
                    app.store.mirrorMode.collect { mode ->
                        if (mode == MirrorMode.SHOWN && !app.store.calibrateMode.value) {
                            // nothing to do here; the overlay shows the mirror
                        }
                    }
                }
                launch {
                    app.shizuku.state.collect { refreshPermissionRows() }
                }
            }
        }
    }

    private fun renderCalibration(point: ClickPoint?) {
        binding.calibrateStatus.text = if (point == null) {
            "No point yet"
        } else {
            "Point: (${point.x}, ${point.y})"
        }
        if (point != null && app.store.calibrateMode.value) {
            app.store.setCalibrateMode(false)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun shizukuGranted(): Boolean = app.shizuku.permissionGranted && app.shizuku.isBinderAlive

    private fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun allGranted(): Boolean = shizukuGranted() && overlayGranted() && notificationsGranted()

    private fun refreshPermissionRows() {
        binding.shizukuStatus.text = status(shizukuGranted())
        binding.overlayStatus.text = status(overlayGranted())
        binding.notificationsStatus.text = status(notificationsGranted())
        binding.shizukuButton.isEnabled = !shizukuGranted()
        binding.overlayButton.isEnabled = !overlayGranted()
        binding.notificationsButton.isEnabled = !notificationsGranted()
        if (!allGranted()) {
            app.store.setStatusDetail("Grant every permission below, then start the session")
        }
    }

    private fun status(granted: Boolean): String =
        getString(if (granted) com.sabeeir.catchapp.R.string.setup_granted
        else com.sabeeir.catchapp.R.string.setup_missing)

    // ------------------------------------------------------------------ session

    private fun requestProjection() {
        if (!allGranted()) {
            app.store.setStatusDetail("Missing permissions — see the list above")
            refreshPermissionRows()
            return
        }
        consentLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startSession(resultCode: Int, data: Intent) {
        val intent = Intent(this, CatchService::class.java)
            .setAction(CatchActions.ACTION_START)
            .putExtra(CatchActions.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CatchActions.EXTRA_RESULT_DATA, data)
            .putExtra(CatchActions.EXTRA_WIDTH, DisplayConfig.DEFAULT_WIDTH)
            .putExtra(CatchActions.EXTRA_HEIGHT, DisplayConfig.DEFAULT_HEIGHT)
            .putExtra(CatchActions.EXTRA_DPI, DisplayConfig.DEFAULT_DPI)
        ContextCompat.startForegroundService(this, intent)
        sendTools()
        app.store.setStatusDetail("Starting…")
    }

    private fun sendTools() {
        if (!app.store.serviceRunning.value) return
        sendToService(CatchActions.ACTION_APPLY_TOOLS)
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, CatchService::class.java).setAction(action)
        if (action == CatchActions.ACTION_APPLY_TOOLS) {
            intent
                .putExtra(CatchActions.EXTRA_CPS, binding.cpsSlider.value)
                .putExtra(CatchActions.EXTRA_MAX_CLICKS, app.settings.maxClicks)
                .putExtra(CatchActions.EXTRA_MAX_MINUTES, app.settings.maxMinutes)
                .putExtra(CatchActions.EXTRA_ANTI_IDLE, binding.antiIdleSwitch.isChecked)
                .putExtra(CatchActions.EXTRA_ANTI_IDLE_MINUTES, binding.antiIdleSlider.value.toInt())
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            app.store.setStatusDetail("Could not reach the service: $t")
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private fun runSelfTest() {
        binding.diagnosticOutput.text = "Running self test…"
        lifecycleScope.launch {
            val report: SelfTestReport? = app.shizuku.selfTest()
            binding.diagnosticOutput.text = when {
                report == null -> "Shizuku unreachable. Start Shizuku and grant permission."
                else -> report.summary()
            }
        }
    }

    private fun runKeepAlive() {
        binding.diagnosticOutput.text = "Applying keep-alive tuning…"
        lifecycleScope.launch {
            val tuner = KeepAliveTuner { command -> app.shizuku.exec(command) }
            val results = tuner.apply()
            binding.diagnosticOutput.text = results.joinToString("\n")
        }
    }
}
