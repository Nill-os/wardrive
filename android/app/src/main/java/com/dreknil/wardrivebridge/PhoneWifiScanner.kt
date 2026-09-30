package com.dreknil.wardrivebridge

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodically triggers a WiFi scan with the phone's own radio and reports
 * results, same data WardriveGo would gather itself - this app collects it
 * too so "everything the phone gathers" ends up in the same combined feed
 * as the rig's data, not a separate silo.
 *
 * Android throttles startScan() heavily (a handful of calls per 2 minutes
 * for background apps, looser in the foreground) - calling it on a timer
 * and just accepting the OS's own rate limit is the standard approach; no
 * crash risk from calling "too often", just fewer scans actually happen.
 */
class PhoneWifiScanner(private val context: Context, private val listener: (Observation) -> Unit) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            emitResults()
        }
    }

    private val scanTrigger = object : Runnable {
        override fun run() {
            if (!running) return
            // Emit whatever the OS already has cached FIRST. Android throttles
            // startScan() to a few calls per 2 min, and when it's throttled the
            // SCAN_RESULTS_AVAILABLE broadcast may never fire - so relying only on
            // that broadcast left WiFi permanently empty on a throttled phone.
            // Reading the cached results every tick surfaces every nearby AP
            // regardless of whether a fresh scan actually ran.
            emitResults()
            @Suppress("DEPRECATION")
            wifiManager.startScan()   // ask for a fresh sweep; the receiver picks it up if it runs
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start() {
        // Even if we're already scanning (e.g. reopening a tool that left the
        // scanner running), always re-emit the OS's cached APs right now so the
        // list fills instantly instead of looking dead until the next tick.
        if (running) { emitResults(); return }
        running = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        emitResults()             // surface the OS's already-cached APs immediately, don't wait for a scan
        handler.post(scanTrigger)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(scanTrigger)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION before start()
    private fun emitResults() {
        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            return
        }
        for (r in results) {
            listener(
                Observation(
                    source = Source.PHONE_WIFI,
                    mac = r.BSSID ?: continue,
                    label = r.SSID ?: "",
                    authOrType = authFromCapabilities(r.capabilities ?: ""),
                    channel = frequencyToChannel(r.frequency),
                    frequencyMHz = r.frequency,
                    rssi = r.level,
                    lat = 0.0, lon = 0.0, altitudeM = 0.0, accuracyM = 0.0,
                    firstSeenIso = isoFormat.format(Date()),
                    timestampMs = System.currentTimeMillis(),
                    channelWidthMHz = channelWidthMHz(r.channelWidth),
                    hidden = r.SSID.isNullOrEmpty(),
                    isPineapple = DeviceSignatureDetection.isPineapple(r.SSID ?: ""),
                )
            )
        }
    }

    private fun authFromCapabilities(caps: String): String = when {
        caps.contains("WPA3") -> "WPA3"
        caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP"
        else -> "OPEN"
    }

    private fun frequencyToChannel(freqMHz: Int): Int = when {
        freqMHz == 2484 -> 14
        freqMHz in 2412..2472 -> (freqMHz - 2407) / 5           // 2.4 GHz, channels 1-13
        freqMHz in 5000..5895 -> (freqMHz - 5000) / 5           // 5 GHz
        freqMHz in 5925..7125 -> (freqMHz - 5950) / 5           // 6 GHz (WiFi 6E)
        else -> 0
    }

    private fun channelWidthMHz(width: Int): Int = when (width) {
        android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80 // 80+80, reported as two 80MHz segments
        else -> 0
    }

    companion object {
        // Re-read the OS cache this often. startScan() itself is throttled by
        // Android (~4/2min), but reading cached results is free, so a short
        // interval keeps the list fresh and self-heals a momentarily-empty cache.
        private const val SCAN_INTERVAL_MS = 5000L
    }
}
