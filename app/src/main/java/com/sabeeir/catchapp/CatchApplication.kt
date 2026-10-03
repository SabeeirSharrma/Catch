package com.sabeeir.catchapp

import android.app.Application
import android.content.Context
import android.util.Log
import com.sabeeir.catchapp.core.state.SessionStore
import com.sabeeir.catchapp.core.settings.SettingsStore
import com.sabeeir.catchapp.shell.ShizukuSession
import kotlin.system.exitProcess

/**
 * Process-wide singletons. Everything that must survive an Activity (Shizuku binder
 * tracking, session state, settings) lives here.
 */
class CatchApplication : Application() {

    lateinit var shizuku: ShizukuSession
        private set

    lateinit var store: SessionStore
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        settings = SettingsStore(this)
        store = SessionStore()
        shizuku = ShizukuSession().also { it.start() }
    }

    /**
     * Captures the stack of anything uncaught before the process dies.
     *
     * Release builds are not debuggable, so this is the only way a crash can ever be
     * inspected: Diagnostics offers the file on a share sheet. The default handler still runs
     * afterwards - containing an unknown failure would be worse than reporting it.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Must never throw: this is already the failure path.
            runCatching { CrashLog.record(this, throwable) }
                .onFailure { Log.w(TAG, "could not record crash", it) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                exitProcess(1)
            }
        }
    }

    companion object {
        private const val TAG = "CatchApplication"

        @Volatile
        private var instance: CatchApplication? = null

        fun from(context: Context): CatchApplication =
            (context.applicationContext as? CatchApplication)
                ?: error("CatchApplication not installed")
    }
}
