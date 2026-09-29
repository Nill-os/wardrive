package com.dreknil.wardrivebridge

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile: tap to start or stop a run from the notification shade. */
class RunTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        if (ScanService.stopRunIfRunning()) {
            update()
            return
        }
        // Starting needs the app in front (see ScanService.stopRunIfRunning()).
        val intent = ScanService.startRunIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val s = ScanService.instance
        val running = s?.running == true
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wardrive"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_radar)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) "WiFi ${s?.wifiCountThisRun} · BT ${s?.bleCountThisRun}" else "Stopped"
        }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, RunTileService::class.java))
        }
    }
}
