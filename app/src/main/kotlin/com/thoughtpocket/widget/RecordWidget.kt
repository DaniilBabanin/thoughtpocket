package com.thoughtpocket.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import com.thoughtpocket.R
import com.thoughtpocket.service.PermissionActivity
import com.thoughtpocket.service.RecordState
import com.thoughtpocket.service.RecordingService

/** Stateless home-screen widget: tap to record, tap to stop. Driven by [RecordState]. */
class RecordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, mgr, id)
    }

    companion object {
        private val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Re-render every placed widget — called by the service on state changes. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            for (id in ids) render(context, mgr, id)
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val status = RecordState.status.value
            val recording = status.state == RecordState.State.RECORDING
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            if (recording) {
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_stop)
                views.setViewVisibility(R.id.widget_label, View.GONE)
                views.setViewVisibility(R.id.widget_timer, View.VISIBLE)
                views.setChronometer(R.id.widget_timer, status.startedAtElapsedRealtime, null, true)
                views.setOnClickPendingIntent(R.id.widget_root, stopIntent(context))
            } else {
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_notify)
                views.setViewVisibility(R.id.widget_label, View.VISIBLE)
                views.setViewVisibility(R.id.widget_timer, View.GONE)
                views.setChronometer(R.id.widget_timer, 0L, null, false)
                views.setTextViewText(R.id.widget_label, "Record")
                views.setOnClickPendingIntent(R.id.widget_root, startIntent(context))
            }
            mgr.updateAppWidget(id, views)
        }

        private fun startIntent(context: Context): PendingIntent {
            val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            return if (hasMic) {
                PendingIntent.getForegroundService(
                    context, 1, RecordingService.startIntent(context), flags
                )
            } else {
                val i = Intent(context, PermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                PendingIntent.getActivity(context, 1, i, flags)
            }
        }

        private fun stopIntent(context: Context): PendingIntent =
            PendingIntent.getService(context, 2, RecordingService.stopIntent(context), flags)
    }
}
