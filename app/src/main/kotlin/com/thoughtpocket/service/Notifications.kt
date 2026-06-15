package com.thoughtpocket.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thoughtpocket.R
import com.thoughtpocket.ui.MainActivity

object Notifications {
    const val CHANNEL = "recording"
    const val ONGOING_ID = 1
    const val DONE_ID = 2

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice notes", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** [text] is the status line; [body], when set, shows the live transcript (expandable in the shade). */
    fun ongoing(ctx: Context, text: String, body: String? = null) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ThoughtPocket")
            .setContentText(if (body.isNullOrBlank()) text else body)
            .apply {
                if (!body.isNullOrBlank()) {
                    setSubText(text)
                    setStyle(NotificationCompat.BigTextStyle().bigText(body))
                }
            }
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun done(ctx: Context, text: String) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ThoughtPocket")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }
}
