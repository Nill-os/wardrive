package com.dreknil.wardrivebridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/** Home-screen widget: run state and this run's counts, with a START/STOP button. The service
 *  pushes updates (see ScanService.updateExternalUi) rather than the widget polling. */
class RunWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            ScanService.stopRunIfRunning()
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_STOP = "com.dreknil.wardrivebridge.WIDGET_STOP"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RunWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val s = ScanService.instance
            val running = s?.running == true
            val views = RemoteViews(context.packageName, R.layout.widget_run)
            views.setTextViewText(R.id.widgetStatus, when {
                !running -> "STOPPED"
                s?.paused == true -> "PAUSED"
                else -> "SCANNING" + if (s?.rigConnected == true) " · RIG" else ""
            })
            views.setTextColor(R.id.widgetStatus, if (running) 0xFF22C55E.toInt() else 0xFF4A607A.toInt())
            views.setTextViewText(R.id.widgetCounts, "WIFI ${s?.wifiCountThisRun ?: 0} · BT ${s?.bleCountThisRun ?: 0}")
            views.setTextViewText(R.id.widgetButton, if (running) "> STOP" else "> START")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
            // STOP works in place; START opens the app, which starts the run (Android 14+ won't
            // start a location service from a widget in the background).
            val button = if (running) {
                PendingIntent.getBroadcast(context, 0, Intent(context, RunWidgetProvider::class.java).setAction(ACTION_STOP), flags)
            } else {
                PendingIntent.getActivity(context, 1, ScanService.startRunIntent(context), flags)
            }
            views.setOnClickPendingIntent(R.id.widgetButton, button)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            views.setOnClickPendingIntent(R.id.widgetTitle, open)
            views.setOnClickPendingIntent(R.id.widgetCounts, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
