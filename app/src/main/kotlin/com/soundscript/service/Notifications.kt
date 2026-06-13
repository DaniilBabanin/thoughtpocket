package com.soundscript.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.soundscript.R
import com.soundscript.ui.MainActivity

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

    fun ongoing(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("SoundScript")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun done(ctx: Context, text: String) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("SoundScript")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
    }
}
