package com.sabeeir.catchapp

import android.app.Application
import android.content.Context
import com.sabeeir.catchapp.core.state.SessionStore
import com.sabeeir.catchapp.core.settings.SettingsStore
import com.sabeeir.catchapp.shell.ShizukuSession

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
        settings = SettingsStore(this)
        store = SessionStore()
        shizuku = ShizukuSession().also { it.start() }
    }

    companion object {
        @Volatile
        private var instance: CatchApplication? = null

        fun from(context: Context): CatchApplication =
            (context.applicationContext as? CatchApplication)
                ?: error("CatchApplication not installed")
    }
}
