package com.dreknil.wardrivebridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuous BLE scan using the phone's own Bluetooth radio - the other
 * half of "collect everything the phone gathers", alongside PhoneWifiScanner.
 */
class PhoneBleScanner(context: Context, private val listener: (Observation) -> Unit) {
    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager).adapter
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var scanning = false
    // True whenever the caller wants BLE running, independent of whether it's actually managed to
    // start yet - start() used to silently no-op forever if Bluetooth was off (or got toggled off
    // mid-run) with nothing ever retrying, so a run with BT off at the moment Start was tapped
    // permanently reported 0 BLE devices with no error, indistinguishable from "none nearby."
    private var wantScanning = false
    private var receiverRegistered = false
    // Which kind of scan is running: unfiltered with the screen on, filtered with it off.
    private var scanningFiltered = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // Android refuses a 6th scan start within 30 s per app (and the rig link's scans count
    // too) - silently, with no onScanFailed - so starts are rate-guarded and retried.
    private val startTimes = ArrayDeque<Long>()
    private val startRunnable = Runnable { attemptStart() }
    private val restartRunnable = Runnable { restartScan() } // the 25-min refresh
    private val screenRunnable = Runnable { // debounced screen on/off switch (own runnable: must not cancel the refresh)
        if (scanning && !isScreenOn() != scanningFiltered) restartScan()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!wantScanning) return
            if (!scanning) { attemptStart(); return }
            // Debounced: a quick glance at the phone (on/off/on/off) shouldn't burn scan starts.
            handler.removeCallbacks(screenRunnable)
            if (!isScreenOn() != scanningFiltered) handler.postDelayed(screenRunnable, SCREEN_DEBOUNCE_MS)
        }
    }
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> attemptStart()
                // Bluetooth going off drops the scan without a callback - forget it so STATE_ON
                // starts a fresh one.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // Unregister too: the scanner keeps the callback registered across a BT toggle
                    // (BLE stays up with location scanning on), and the restart would then fail
                    // with SCAN_FAILED_ALREADY_STARTED forever.
                    stopScanQuietly()
                    scanning = false
                    handler.removeCallbacks(restartRunnable)
                    handler.removeCallbacks(screenRunnable)
                }
            }
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emit(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) emit(r)
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) stopScanQuietly()
            scanning = false
            handler.removeCallbacks(startRunnable)
            handler.postDelayed(startRunnable, RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_SCAN/BLUETOOTH_CONNECT before start()
    private fun emit(result: ScanResult) {
        val device = result.device ?: return
        val name = try {
            device.name ?: result.scanRecord?.deviceName ?: ""
        } catch (e: SecurityException) {
            ""
        }
        val mac = device.address ?: return
        val tracker = looksLikeTracker(result)
        val companyId = firstCompanyId(result)
        val flipper = DeviceSignatureDetection.isFlipperZero(mac, name)
        val flock = DeviceSignatureDetection.isFlockSafety(mac, name, companyId)
        val skimmer = DeviceSignatureDetection.isSkimmer(name)
        val uuids = try {
            result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val drone = DeviceSignatureDetection.isDrone(uuids)
        val meshRadio = DeviceSignatureDetection.isMeshRadio(uuids)
        val glasses = DeviceSignatureDetection.isGlasses(name)
        val actionCam = DeviceSignatureDetection.isActionCam(name)
        val policeCam = DeviceSignatureDetection.isPoliceCam(name)
        listener(
            Observation(
                source = Source.PHONE_BLE,
                mac = mac,
                label = if (tracker && name.isBlank()) "(possible tracker)" else name,
                authOrType = "BLE",
                channel = 0,
                frequencyMHz = 0,
                rssi = result.rssi,
                lat = 0.0, lon = 0.0, altitudeM = 0.0, accuracyM = 0.0,
                firstSeenIso = isoFormat.format(Date()),
                timestampMs = System.currentTimeMillis(),
                isTracker = tracker,
                isFlipperZero = flipper,
                isFlockCamera = flock,
                isSkimmer = skimmer,
                isDrone = drone,
                isMeshRadio = meshRadio,
                isGlasses = glasses,
                isActionCam = actionCam,
                isPoliceCam = policeCam,
                companyId = companyId,
                serviceUuids = uuids,
                connectable = result.isConnectable,
            )
        )
    }

    // See TrackerDetection for the heuristic itself - shared with the rig's
    // relayed BLE data so both sources are flagged the same way.
    private fun looksLikeTracker(result: ScanResult): Boolean {
        val mfg = result.scanRecord?.manufacturerSpecificData ?: return false
        for (i in 0 until mfg.size()) {
            if (TrackerDetection.isTracker(mfg.keyAt(i), mfg.valueAt(i) ?: ByteArray(0))) return true
        }
        return false
    }

    private fun firstCompanyId(result: ScanResult): Int? {
        val mfg = result.scanRecord?.manufacturerSpecificData ?: return null
        return if (mfg.size() > 0) mfg.keyAt(0) else null
    }

    @SuppressLint("MissingPermission")
    fun start() {
        wantScanning = true
        if (!receiverRegistered) {
            appContext.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            appContext.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            })
            receiverRegistered = true
        }
        attemptStart()
    }

    @SuppressLint("MissingPermission")
    private fun attemptStart() {
        if (scanning || !wantScanning) return
        // Bluetooth off (or still coming up) - nothing to do right now, btStateReceiver retries
        // this once BluetoothAdapter.ACTION_STATE_CHANGED reports STATE_ON.
        val scanner = adapter?.bluetoothLeScanner ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > START_WINDOW_MS) startTimes.removeFirst()
        if (startTimes.size >= MAX_STARTS_PER_WINDOW) { // try again once the oldest start ages out
            handler.removeCallbacks(startRunnable)
            handler.postDelayed(startRunnable, START_WINDOW_MS - (now - startTimes.first()) + 500)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Android 8.1+ suspends an *unfiltered* BLE scan while the screen is off (seen on the
        // Pixel: suspended ~90% of the time with the phone locked), and an all-empty filter
        // counts as unfiltered. So: unfiltered with the screen on (sees everything), and with it
        // off a set of real filters that still catches most devices - see SCREEN_OFF_FILTERS.
        scanningFiltered = !isScreenOn()
        try {
            scanner.startScan(if (scanningFiltered) SCREEN_OFF_FILTERS else null, settings, callback)
        } catch (_: Exception) { // e.g. Bluetooth turning off right now
            handler.removeCallbacks(startRunnable)
            handler.postDelayed(startRunnable, RETRY_MS)
            return
        }
        startTimes.addLast(now)
        scanning = true
        // Android demotes any regular scan to opportunistic after 30 min; restart before that so
        // a long drive with the phone locked keeps scanning.
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, REFRESH_MS)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantScanning = false
        handler.removeCallbacks(startRunnable)
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(screenRunnable)
        if (receiverRegistered) {
            appContext.unregisterReceiver(btStateReceiver)
            appContext.unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        stopScanQuietly() // unconditionally: a failed/half-started scan may still be registered
        scanning = false
    }

    private fun isScreenOn(): Boolean =
        (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    @SuppressLint("MissingPermission")
    private fun stopScanQuietly() {
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
    }

    private fun restartScan() {
        stopScanQuietly()
        scanning = false
        attemptStart()
    }

    companion object {
        private const val START_WINDOW_MS = 30_000L
        // Android allows 5 per app; the rig link's search cycles its own scan about every 13 s
        // while the rig isn't connected (~3 starts per 30 s), so this scanner keeps to 2.
        private const val MAX_STARTS_PER_WINDOW = 2
        private const val SCREEN_DEBOUNCE_MS = 2_000L
        private const val RETRY_MS = 5_000L
        private const val REFRESH_MS = 25 * 60_000L

        // Screen-off scan filters (they OR together). A service-UUID filter with an all-zero mask
        // matches any device that advertises at least one service (trackers, Fast Pair, wearables,
        // Flipper, most IoT); the manufacturer filters catch the big makers' devices that
        // advertise only manufacturer data (Apple Find My / AirTags, Samsung SmartTags, ...).
        private val SCREEN_OFF_FILTERS: List<ScanFilter> = buildList {
            val zero = android.os.ParcelUuid(java.util.UUID(0L, 0L))
            add(ScanFilter.Builder().setServiceUuid(zero, zero).build())
            for (company in intArrayOf(
                0x004C, // Apple
                0x0075, // Samsung
                0x00E0, // Google
                0x0006, // Microsoft
                0x01AB, // Meta (glasses)
                0x08AA, // DJI (drones)
                0x0087, // Garmin
                0x0171, // Amazon
                0x038F, // Xiaomi
                0x027D, // Huawei
                0x012D, // Sony
                0x009E, // Bose
                0x02F2, // GoPro
            )) add(ScanFilter.Builder().setManufacturerData(company, ByteArray(0)).build())
        }
    }

    // BluetoothManager.adapter is backed by the same platform default adapter
    // BluetoothAdapter.getDefaultAdapter() returns - redundant to check both.
    fun isSupported(): Boolean = adapter != null
}
