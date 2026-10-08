package com.dreknil.wardrivebridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE transport to cyd_node - the phone's primary link to the rig, with USB serial as the
 * fallback (see RigLinkManager). cyd_node is the peripheral (CydBleLink's server role); this is
 * the central. Same '\n'-terminated wdstream line protocol as the USB path, just carried over a
 * NUS-style RX/TX characteristic pair instead of a serial port.
 *
 * Reconnects on its own indefinitely: a drop (out of range, cyd_node rebooting, its RE-LINK
 * button, the BLE stack pause around every upload) goes straight back to scanning.
 *
 * Pairing: this phone only ever talks to the one rig it bonded with (AppSettings.pairedRigAddress),
 * so two rigs and two phones near each other never cross over. With no rig saved yet it only
 * considers a rig advertising an open pairing window (its owner just tapped PAIR PHONE), bonds
 * with it - Android asks for the 6-digit code shown on the rig's screen - and saves it.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
class RigBleLink(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** Link fully up - notifications subscribed, ready to send. */
        fun onBleConnected()
        fun onBleDisconnected()
        /** Raw bytes from cyd_node, in arrival order; lines may span calls. Binder thread. */
        fun onBleData(data: ByteArray)
        fun onBleLog(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settings = AppSettings(context)
    private var bondingDevice: BluetoothDevice? = null

    // Connections to the saved rig that the rig itself rejected: up for a while but never
    // secured, then dropped. The rig drops a phone it has no bond for after ~10s (after its
    // FORGET PHONES), so a run of these means "pair again". Quick drops - the rig rebooting,
    // the phone at the edge of range - don't count; counting those unpaired a phone that had
    // simply driven away from the rig.
    private var failedAttempts = 0
    private var connectStartedMs = 0L

    /** True when no rig is paired yet - the UI shows a pairing hint instead of just DOWN. */
    val needsPairing: Boolean get() = settings.pairedRigAddress.isEmpty()
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var mtu = 23

    @Volatile
    var isConnected = false
        private set

    // Android allows one outstanding GATT operation at a time - queued and drained from
    // onCharacteristicWrite(), otherwise a second write issued mid-flight is silently dropped.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    fun start() {
        if (running) return
        running = true
        startScan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        stopBondWatch()
    }

    /** Forget the paired rig: drop the link and go back to waiting for a pairing window. */
    fun forgetRig() {
        settings.pairedRigAddress = ""
        closeGatt()
        stopScan()
        scheduleRescan(RECONNECT_DELAY_MS)
    }

    fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray()
        val chunk = (mtu - 3).coerceIn(20, 240)
        synchronized(writeQueue) {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunk, bytes.size)
                writeQueue.add(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
        drainWrites()
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val retryScan = Runnable { startScan() }

    private fun scheduleRescan(delayMs: Long) {
        handler.removeCallbacks(retryScan)
        if (running) handler.postDelayed(retryScan, delayMs)
    }

    private fun startScan() {
        if (!running || isConnected || gatt != null) return
        if (adapter == null || !adapter.isEnabled || !hasPermissions()) {
            // Bluetooth off or permission not granted yet - check again later rather than give up.
            scheduleRescan(RETRY_MS)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { scheduleRescan(RETRY_MS); return }
        // Paired: only ever our own rig, by address. Not paired: any rig by service UUID, then
        // onScanResult() keeps only ones with an open pairing window.
        val paired = settings.pairedRigAddress
        val filters = if (paired.isNotEmpty()) {
            listOf(ScanFilter.Builder().setDeviceAddress(paired).build())
        } else {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            callbacks.onBleLog("[ble] scan failed to start: ${e.message}")
            scheduleRescan(RETRY_MS)
            return
        }
        // Cycled rather than left open forever - Android throttles apps that restart scans more
        // than 5 times per 30s, and a long-running scan can be quietly deprioritized, so a
        // bounded window with a fresh restart is the dependable pattern.
        handler.removeCallbacks(scanWindowEnd)
        handler.postDelayed(scanWindowEnd, SCAN_WINDOW_MS)
    }

    // Named so a leftover from an earlier scan can't cut a newer one short (each extra restart
    // spends the app's 5-starts-per-30s scan budget, shared with PhoneBleScanner).
    private val scanWindowEnd = Runnable {
        if (scanning && !isConnected && gatt == null) {
            stopScan()
            scheduleRescan(1_000)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanWindowEnd)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val paired = settings.pairedRigAddress
            if (paired.isNotEmpty()) {
                if (result.device.address.equals(paired, ignoreCase = true)) handler.post { connect(result.device) }
                return
            }
            val mfg = result.scanRecord?.getManufacturerSpecificData(MFG_COMPANY_ID)
            val pairingOpen = mfg != null && mfg.size >= 3 && mfg[0] == 'W'.code.toByte() &&
                mfg[1] == 'D'.code.toByte() && mfg[2] == 1.toByte()
            // Already bonded with Android = the code was entered for this rig at some point (the
            // app may have been closed before it could save it) - adopt it.
            val alreadyBonded = result.device.bondState == BluetoothDevice.BOND_BONDED
            if (pairingOpen || alreadyBonded) handler.post { pair(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            callbacks.onBleLog("[ble] scan failed: $errorCode")
            scheduleRescan(RETRY_MS)
        }
    }

    private fun pair(device: BluetoothDevice) {
        if (!running || gatt != null || bondingDevice != null || settings.pairedRigAddress.isNotEmpty()) return
        stopScan()
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            pairedWith(device)
            return
        }
        callbacks.onBleLog("[ble] rig ${device.address} is in pairing mode - enter the code shown on its screen")
        bondingDevice = device
        bondPollStartedAt = System.currentTimeMillis()
        startBondWatch()
        if (!device.createBond()) {
            callbacks.onBleLog("[ble] couldn't start pairing")
            stopBondWatch()
            scheduleRescan(RETRY_MS)
        }
    }

    /** Drops Android's stale bond so the next pairing starts clean. removeBond() is hidden API,
     *  so best-effort - if it fails the user can remove the rig in Bluetooth settings. */
    private fun forgetBond(device: BluetoothDevice) {
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
        } catch (_: Exception) {
        }
    }

    private fun pairedWith(device: BluetoothDevice) {
        if (settings.pairedRigAddress.equals(device.address, ignoreCase = true) && gatt != null) return
        settings.pairedRigAddress = device.address
        callbacks.onBleLog("[ble] paired with rig ${device.address}")
        connect(device)
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device == null || device.address != bondingDevice?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    stopBondWatch()
                    pairedWith(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    callbacks.onBleLog("[ble] pairing cancelled or wrong code")
                    stopBondWatch()
                    scheduleRescan(RETRY_MS)
                }
            }
        }
    }
    private var bondWatchRegistered = false

    private fun startBondWatch() {
        if (bondWatchRegistered) return
        // EXPORTED: bond-state broadcasts come from the Bluetooth app's process, not the system
        // server, and a NOT_EXPORTED receiver never hears them.
        ContextCompat.registerReceiver(
            context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        bondWatchRegistered = true
        handler.postDelayed(bondPoll, 1_000)
    }

    // Belt and braces for the receiver: check the bond directly once a second while pairing.
    private val bondPoll = object : Runnable {
        override fun run() {
            val d = bondingDevice ?: return
            when (d.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    stopBondWatch()
                    pairedWith(d)
                }
                BluetoothDevice.BOND_BONDING -> handler.postDelayed(this, 1_000)
                else -> if (bondPollStartedAt != 0L && System.currentTimeMillis() - bondPollStartedAt > 5_000) {
                    stopBondWatch()
                    scheduleRescan(RETRY_MS)
                } else handler.postDelayed(this, 1_000)
            }
        }
    }
    private var bondPollStartedAt = 0L

    private fun stopBondWatch() {
        bondingDevice = null
        handler.removeCallbacks(bondPoll)
        if (!bondWatchRegistered) return
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: Exception) {
        }
        bondWatchRegistered = false
    }

    private fun connect(device: BluetoothDevice) {
        if (!running || gatt != null) return
        stopScan()
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // Saved rig, but Android no longer has the bond (removed in Bluetooth settings, or the
            // rig forgot its phones) - the rig would refuse us, so start pairing over.
            callbacks.onBleLog("[ble] rig ${device.address} isn't paired any more - pair again from the rig's CFG tab")
            settings.pairedRigAddress = ""
            scheduleRescan(RETRY_MS)
            return
        }
        callbacks.onBleLog("[ble] found ${device.address} - connecting")
        connectStartedMs = System.currentTimeMillis()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        val wasConnected = isConnected
        isConnected = false
        rxChar = null
        mtu = 23
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInFlight = false
        }
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        if (wasConnected) callbacks.onBleDisconnected()
    }

    private fun drainWrites() {
        val g = gatt ?: return
        val c = rxChar ?: return
        val next: ByteArray
        synchronized(writeQueue) {
            if (writeInFlight || writeQueue.isEmpty()) return
            next = writeQueue.poll() ?: return
            writeInFlight = true
        }
        // writeCharacteristic can throw DeadObjectException/RemoteException if
        // the BLE stack or the rig went away between the null-check and here
        // (e.g. the rig rebooted for an OTA/flash). Treat that as a failed
        // write and let the reconnect logic recover, rather than crashing.
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS // API 33 returns a status code (0 too)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = next
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            callbacks.onBleLog("[ble] write threw ${e.javaClass.simpleName} - link lost")
            false
        }
        if (!ok) {
            synchronized(writeQueue) { writeInFlight = false }
            callbacks.onBleLog("[ble] write failed")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Bigger MTU first so each WD:AP line fits in one notification - cyd_node
                // chunks its sends to whatever gets negotiated here.
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    if (gatt === g) {
                        callbacks.onBleLog("[ble] disconnected (status $status)")
                        val rejected = !isConnected &&
                            System.currentTimeMillis() - connectStartedMs >= REJECTED_AFTER_MS
                        if (rejected && ++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                            callbacks.onBleLog("[ble] rig keeps refusing this phone - pair again from the rig's CFG tab")
                            forgetBond(g.device)
                            settings.pairedRigAddress = ""
                            failedAttempts = 0
                        }
                        closeGatt()
                        scheduleRescan(RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            val tx = service?.getCharacteristic(TX_CHAR_UUID)
            val cccd = tx?.getDescriptor(CCCD_UUID)
            if (rx == null || tx == null || cccd == null) {
                callbacks.onBleLog("[ble] wardrive service not found on device - disconnecting")
                handler.post {
                    if (gatt === g) {
                        closeGatt()
                        scheduleRescan(RETRY_MS)
                    }
                }
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            handler.post {
                if (gatt !== g) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    failedAttempts = 0
                    isConnected = true
                    callbacks.onBleLog("[ble] connected (mtu $mtu)")
                    callbacks.onBleConnected()
                    drainWrites()
                } else {
                    callbacks.onBleLog("[ble] notification subscribe failed ($status)")
                    closeGatt()
                    scheduleRescan(RETRY_MS)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeQueue) { writeInFlight = false }
            drainWrites()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX_CHAR_UUID) callbacks.onBleData(value)
        }

        @Deprecated("Pre-API-33 variant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return // the value-carrying overload above handles it
            if (c.uuid == TX_CHAR_UUID) callbacks.onBleData(c.value ?: return)
        }
    }

    companion object {
        // Must match lib/WardriveShared/CydBleLink.cpp and beginServer()'s name in cyd_node.
        const val DEVICE_NAME = "WardriveCYD"
        val SERVICE_UUID: UUID = UUID.fromString("5b60de00-0000-4a6c-9b1a-6364796477b1")
        val RX_CHAR_UUID: UUID = UUID.fromString("5b60de00-0001-4a6c-9b1a-6364796477b1") // phone writes
        val TX_CHAR_UUID: UUID = UUID.fromString("5b60de00-0002-4a6c-9b1a-6364796477b1") // cyd notifies
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 0xFFFF = reserved/testing company ID; payload "WD" + pairing-open flag. See CydBleLink.cpp. */
        private const val MFG_COMPANY_ID = 0xFFFF

        private const val MAX_FAILED_ATTEMPTS = 3
        // The rig drops an unsecured connection after 10s; anything shorter is a normal drop.
        private const val REJECTED_AFTER_MS = 8_000L
        private const val SCAN_WINDOW_MS = 12_000L
        private const val RETRY_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 1_500L
    }
}
