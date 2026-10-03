package com.sabeeir.catchapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the notification actions and forwards them to the running service.
 * Exported = false, so only our own process can poke it.
 */
class CatchActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) {
            Log.w(TAG, "ignoring unknown action $action")
            return
        }
        val serviceIntent = Intent(context, CatchService::class.java).setAction(action)
        try {
            context.startForegroundService(serviceIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "could not dispatch $action: $t")
        }
    }

    companion object {
        private const val TAG = "CatchReceiver"

        private val HANDLED = setOf(
            CatchActions.ACTION_SHOW,
            CatchActions.ACTION_HIDE,
            CatchActions.ACTION_STOP_LOOP,
            CatchActions.ACTION_STOP_ALL,
            CatchActions.ACTION_RELAUNCH,
            CatchActions.ACTION_TOGGLE_LOOP,
            CatchActions.ACTION_APPLY_TOOLS,
        )
    }
}
