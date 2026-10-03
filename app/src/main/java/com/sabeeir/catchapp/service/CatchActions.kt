package com.sabeeir.catchapp.service

import android.content.Intent

/**
 * Intent actions understood by [CatchService]. Kept in one place so the notification,
 * the receiver and the Activity all agree.
 */
object CatchActions {

    const val ACTION_START = "com.sabeeir.catchapp.action.START"
    const val ACTION_SHOW = "com.sabeeir.catchapp.action.SHOW"
    const val ACTION_HIDE = "com.sabeeir.catchapp.action.HIDE"
    const val ACTION_TOGGLE_LOOP = "com.sabeeir.catchapp.action.TOGGLE_LOOP"
    const val ACTION_STOP_LOOP = "com.sabeeir.catchapp.action.STOP_LOOP"
    const val ACTION_STOP_ALL = "com.sabeeir.catchapp.action.STOP_ALL"
    const val ACTION_RELAUNCH = "com.sabeeir.catchapp.action.RELAUNCH"
    const val ACTION_APPLY_TOOLS = "com.sabeeir.catchapp.action.APPLY_TOOLS"

    const val EXTRA_RESULT_CODE = "com.sabeeir.catchapp.extra.RESULT_CODE"
    const val EXTRA_RESULT_DATA = "com.sabeeir.catchapp.extra.RESULT_DATA"
    const val EXTRA_WIDTH = "com.sabeeir.catchapp.extra.WIDTH"
    const val EXTRA_HEIGHT = "com.sabeeir.catchapp.extra.HEIGHT"
    const val EXTRA_DPI = "com.sabeeir.catchapp.extra.DPI"
    const val EXTRA_CPS = "com.sabeeir.catchapp.extra.CPS"
    const val EXTRA_MAX_CLICKS = "com.sabeeir.catchapp.extra.MAX_CLICKS"
    const val EXTRA_MAX_MINUTES = "com.sabeeir.catchapp.extra.MAX_MINUTES"
    const val EXTRA_ANTI_IDLE = "com.sabeeir.catchapp.extra.ANTI_IDLE"
    const val EXTRA_ANTI_IDLE_MINUTES = "com.sabeeir.catchapp.extra.ANTI_IDLE_MINUTES"

    fun forService(action: String): Intent = Intent().setAction(action)

    fun hasAction(intent: Intent?, action: String): Boolean = intent?.action == action
}

/** Everything the notification needs to render itself. */
data class NotificationState(
    val bubbleState: com.sabeeir.catchapp.core.BubbleState,
    val mirrorShown: Boolean,
    val loopActive: Boolean,
    val robloxAlive: Boolean,
    val displayAlive: Boolean,
)
