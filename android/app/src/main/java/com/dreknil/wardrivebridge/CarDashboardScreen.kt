package com.dreknil.wardrivebridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The only screen this car app has - a read-only status readout (rig link,
 * GPS fix, live counts, session distance), refreshed on a timer rather than
 * per-observation. Car hosts throttle/reject templates that invalidate too
 * often (driver-distraction policy, not a bug to work around) - 2s is safely
 * under that, and this is a glance-while-driving readout anyway, not
 * something that needs sub-second updates.
 *
 * Deliberately doesn't register as ScanService.SessionListener - that slot
 * is a single nullable field MainActivity already owns (see
 * ScanService.listener), and grabbing it here would silently steal live
 * updates away from the phone screen if both are open at once. Polling
 * ScanService's already-public state instead sidesteps that entirely and
 * happens to be exactly the throttled cadence the car host wants anyway.
 */
class CarDashboardScreen(carContext: CarContext) : Screen(carContext) {
    private var scanService: ScanService? = null
    private val pollHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            scanService = (service as ScanService.LocalBinder).service
            invalidate()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            scanService = null
            invalidate()
        }
    }

    private val pollTick = object : Runnable {
        override fun run() {
            invalidate()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carContext.bindService(Intent(carContext, ScanService::class.java), connection, Context.BIND_AUTO_CREATE)
                pollHandler.post(pollTick)
            }

            override fun onStop(owner: LifecycleOwner) {
                pollHandler.removeCallbacks(pollTick)
                try {
                    carContext.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                    // Never successfully bound (e.g. torn down before onServiceConnected) - fine to ignore.
                }
                scanService = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val service = scanService
        val pane = Pane.Builder()

        pane.addRow(
            Row.Builder()
                .setTitle("Rig")
                .addText(rigText(service))
                .build()
        )

        // Only a fix fresh enough to log with counts (see LocationTracker.hasFix()).
        val loc = service?.locationTracker?.lastLocation?.takeIf { service.locationTracker.hasFix() }
        pane.addRow(
            Row.Builder()
                .setTitle("GPS")
                .addText(if (loc != null) "Fix (%.4f, %.4f)".format(loc.latitude, loc.longitude) else "No fix")
                .build()
        )

        val groups = service?.groups
        val countsText = if (groups == null) {
            "0 WiFi · 0 BLE"
        } else {
            // Union of MACs across rig+phone for each radio type, same
            // de-duplication as the phone dashboard - see MainActivity's
            // updateStatusText() for why a raw per-source sum double-counts
            // any device both radios detect.
            val wifi = (groups.getValue(Source.RIG_WIFI).keys + groups.getValue(Source.PHONE_WIFI).keys).size
            val ble = (groups.getValue(Source.RIG_BLE).keys + groups.getValue(Source.PHONE_BLE).keys).size
            "$wifi WiFi · $ble BLE"
        }
        pane.addRow(Row.Builder().setTitle("Found this run").addText(countsText).build())

        val running = service?.running == true
        val paused = service?.paused == true
        val sessionText = when {
            !running -> "Not running"
            paused -> "Paused · %.2f mi".format(service!!.totalDistanceMeters / 1609.34)
            else -> "Running · %.2f mi".format(service!!.totalDistanceMeters / 1609.34)
        }
        pane.addRow(Row.Builder().setTitle("Session").addText(sessionText).build())

        // At most 2 actions on a Pane - contextual by state instead of
        // trying to cram Start/Stop/Pause/Resume in at once: mirrors the
        // phone dashboard's own state-dependent button (see MainActivity's
        // startStopButton/pauseResumeButton).
        if (!running) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Start")
                    .setOnClickListener {
                        scanService?.startRun(notifyRig = true, reason = "Android Auto Start")
                        invalidate()
                    }
                    .build()
            )
        } else {
            pane.addAction(
                Action.Builder()
                    .setTitle(if (paused) "Resume" else "Pause")
                    .setOnClickListener {
                        val svc = scanService ?: return@setOnClickListener
                        if (svc.paused) svc.resumeRun() else svc.pauseRun()
                        invalidate()
                    }
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Stop")
                    .setOnClickListener {
                        scanService?.stopRun(notifyRig = true, reason = "Android Auto Stop")
                        invalidate()
                    }
                    .build()
            )
        }

        return PaneTemplate.Builder(pane.build())
            .setHeaderAction(Action.APP_ICON)
            .setTitle("Wardrive Bridge")
            .build()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
    }

    private fun rigText(service: ScanService?): String {
        if (service?.rigConnected != true) return "Not connected"
        val h = service.rigHealth?.takeIf { System.currentTimeMillis() - it.atMs < 10_000 } ?: return "Connected"
        val gps = if (h.gpsFix) "GPS ${if (h.sats >= 0) "${h.sats} sats" else "fix"}" else "GPS no fix"
        val sd = if (h.sdOk) "SD OK" else "SD FAIL"
        return "Connected · $gps · $sd" + if (h.pendingUploads > 0) " · ${h.pendingUploads} to upload" else ""
    }
}
