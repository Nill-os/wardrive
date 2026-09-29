package com.dreknil.wardrivebridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                attemptStart()
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
            scanning = false
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        scanning = true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wantScanning = false
        if (receiverRegistered) {
            appContext.unregisterReceiver(btStateReceiver)
            receiverRegistered = false
        }
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        scanning = false
    }

    // BluetoothManager.adapter is backed by the same platform default adapter
    // BluetoothAdapter.getDefaultAdapter() returns - redundant to check both.
    fun isSupported(): Boolean = adapter != null
}
