package com.sabeeir.catchapp.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.sabeeir.catchapp.BuildConfig
import com.sabeeir.catchapp.core.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Owns the Shizuku lifecycle on the app side: binder tracking, permission, binding the
 * [ShellUserService] (which runs as shell) and a small suspend API over it.
 *
 * Every call degrades instead of throwing: when Shizuku is gone, [exec] returns an
 * error [ExecResult] and [service] simply becomes null.
 */
class ShizukuSession(
    private val packageName: String = BuildConfig.APPLICATION_ID,
    private val versionCode: Int = BuildConfig.VERSION_CODE,
) {

    enum class BinderState { UNAVAILABLE, AVAILABLE }

    private val _state = MutableStateFlow(BinderState.UNAVAILABLE)
    val state: StateFlow<BinderState> = _state

    private val _service = MutableStateFlow<IShellService?>(null)

    /** Non-null only while the shell-side user service is connected. */
    val service: StateFlow<IShellService?> = _service

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(packageName, ShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(versionCode)
        .tag(TAG)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IShellService.Stub.asInterface(it) }
            Log.i(TAG, "user service connected: $name")
            _service.value = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "user service disconnected: $name")
            _service.value = null
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "shizuku binder received")
        _state.value = BinderState.AVAILABLE
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "shizuku binder dead")
        _state.value = BinderState.UNAVAILABLE
        _service.value = null
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            permissionListenerCallback?.invoke()
        }

    /** Set by the UI so it can refresh the permission row. */
    @Volatile
    var permissionListenerCallback: (() -> Unit)? = null

    val isBinderAlive: Boolean get() = _state.value == BinderState.AVAILABLE && Shizuku.pingBinder()

    val permissionGranted: Boolean
        get() = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    val shizukuVersion: Int get() = runCatching { Shizuku.getVersion() }.getOrDefault(0)

    val isSupported: Boolean get() = !runCatching { Shizuku.isPreV11() }.getOrDefault(true)

    /** Registers the sticky listeners. Call once from the process entry points. */
    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (Shizuku.pingBinder()) _state.value = BinderState.AVAILABLE
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unbind()
    }

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { Log.w(TAG, "requestPermission failed: $it") }
    }

    /** Binds the shell user service if needed and returns it, or null. */
    suspend fun ensureService(timeoutMs: Long = 10_000): IShellService? {
        _service.value?.let { return it }
        if (!isBinderAlive || !permissionGranted) return null
        bind()
        return withTimeoutOrNull(timeoutMs) { _service.first { it != null } }
    }

    private fun bind() {
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { Log.w(TAG, "bindUserService failed: $it") }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed: $it") }
        _service.value = null
    }

    /** Runs a privileged command through Shizuku. Never throws. */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ExecResult =
        withContext(Dispatchers.IO) {
            val service = ensureService()
                ?: return@withContext ExecResult.error(command, "shizuku unavailable")
            try {
                ExecResult.decode(service.exec(command, timeoutMs), command)
            } catch (t: Throwable) {
                _service.value = null
                ExecResult.error(command, t.message ?: t.javaClass.simpleName ?: "error")
            }
        }

    /** Capability report from the shell process, or null when unreachable. */
    suspend fun selfTest(): SelfTestReport? = withContext(Dispatchers.IO) {
        val service = ensureService() ?: return@withContext null
        try {
            SelfTestReport.parse(service.selfTest())
        } catch (t: Throwable) {
            Log.w(TAG, "selfTest failed: $t")
            null
        }
    }

    /** @return an [InputEngine] style code, or [InputEngine.ERR_UNSUPPORTED] when gone. */
    suspend fun tap(x: Int, y: Int, displayId: Int): Int = withContext(Dispatchers.IO) {
        val service = ensureService(timeoutMs = 3_000)
            ?: return@withContext InputEngine.ERR_UNSUPPORTED
        try {
            service.tap(x, y, displayId)
        } catch (t: Throwable) {
            _service.value = null
            Log.w(TAG, "tap failed: $t")
            InputEngine.ERR_UNSUPPORTED
        }
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): Int =
        withContext(Dispatchers.IO) {
            val service = ensureService(timeoutMs = 3_000)
                ?: return@withContext InputEngine.ERR_UNSUPPORTED
            try {
                service.swipe(x1, y1, x2, y2, durationMs, displayId)
            } catch (t: Throwable) {
                _service.value = null
                Log.w(TAG, "swipe failed: $t")
                InputEngine.ERR_UNSUPPORTED
            }
        }

    suspend fun pointer(action: PointerAction, x: Int, y: Int, displayId: Int): Int =
        withContext(Dispatchers.IO) {
            val service = ensureService(timeoutMs = 3_000)
                ?: return@withContext InputEngine.ERR_UNSUPPORTED
            try {
                when (action) {
                    PointerAction.DOWN -> service.pointerDown(x, y, displayId)
                    PointerAction.MOVE -> service.pointerMove(x, y, displayId)
                    PointerAction.UP -> service.pointerUp(x, y, displayId)
                }
            } catch (t: Throwable) {
                _service.value = null
                Log.w(TAG, "pointer $action failed: $t")
                InputEngine.ERR_UNSUPPORTED
            }
        }

    enum class PointerAction { DOWN, MOVE, UP }

    companion object {
        private const val TAG = "CatchShizuku"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val PERMISSION_REQUEST_CODE = 4212
    }
}
