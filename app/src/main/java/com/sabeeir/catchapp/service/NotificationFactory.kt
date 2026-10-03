package com.sabeeir.catchapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sabeeir.catchapp.R
import com.sabeeir.catchapp.core.BubbleState
import com.sabeeir.catchapp.core.BubbleStateReducer
import com.sabeeir.catchapp.ui.MainActivity

/**
 * The persistent notification (spec 5): Show/Hide, Stop loop and Stop all are always
 * reachable, so a user who loses the bubble can always get control back.
 */
class NotificationFactory(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: NotificationState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_color))
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(
                BubbleStateReducer.describe(state.bubbleState) +
                    if (state.loopActive) " · loop on" else "",
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.mirrorShown) {
            builder.addAction(0, context.getString(R.string.action_hide), actionIntent(CatchActions.ACTION_HIDE, 1))
        } else {
            builder.addAction(0, context.getString(R.string.action_show), actionIntent(CatchActions.ACTION_SHOW, 2))
        }

        if (state.loopActive) {
            builder.addAction(
                0,
                context.getString(R.string.action_stop_loop),
                actionIntent(CatchActions.ACTION_STOP_LOOP, 3),
            )
        }

        if (state.robloxAlive.not() && state.displayAlive) {
            builder.addAction(0, context.getString(R.string.action_relaunch), actionIntent(CatchActions.ACTION_RELAUNCH, 4))
        }

        builder.addAction(0, context.getString(R.string.action_stop_all), actionIntent(CatchActions.ACTION_STOP_ALL, 5))
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CatchActionsReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancel() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /** Replaces the persistent notification with the given state. */
    fun publish(state: NotificationState) {
        runCatching { manager.notify(NOTIFICATION_ID, build(state)) }
    }

    companion object {
        const val CHANNEL_ID = "catch_session"
        const val NOTIFICATION_ID = 4711
    }
}
