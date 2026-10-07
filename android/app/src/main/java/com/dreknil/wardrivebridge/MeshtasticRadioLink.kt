package com.dreknil.wardrivebridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Reads the node database of the user's own Meshtastic radio over its Bluetooth API, so a run can
 * log every LoRa mesh node the radio has heard (WDGWars counts Meshtastic nodes, but only a LoRa
 * radio can hear them). The official Meshtastic app removed its third-party service in 2.8, so
 * this talks to the radio directly - and a Meshtastic radio takes one Bluetooth client at a time,
 * so the Meshtastic app must be disconnected from it while this runs.
 *
 * Protocol (meshtastic/firmware BluetoothCommon.h, PhoneAPI): subscribe to FromNum, write
 * ToRadio{want_config_id = 69421 (nodes only)}, then read FromRadio until it comes back empty;
 * every FromNum notification after that means "more to read". Re-asks for the whole node list
 * every few minutes, which also keeps the radio's API session alive. Uses the Bluetooth bond the
 * phone already has with the radio (from pairing it in the Meshtastic app).
 */
@SuppressLint("MissingPermission") // start() checks permissions first
class MeshtasticRadioLink(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onMeshConnected()
        fun onMeshDisconnected()
        /** Main thread. */
        fun onMeshMessage(msg: MeshProto.Message)
        fun onMeshLog(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var address = ""
    private var running = false
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var reading = false
    private var readAgain = false
    private var failures = 0

    @Volatile var isConnected = false
        private set

    fun start(radioAddress: String) {
        if (running) return
        address = radioAddress
        running = true
        connect()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        close()
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    private val retry = Runnable { connect() }

    private fun connect() {
        if (!running || gatt != null) return
        if (adapter == null || !adapter.isEnabled || !hasPermissions() || address.isEmpty()) {
            handler.postDelayed(retry, RETRY_MS)
            return
        }
        val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) { callbacks.onMeshLog("[mesh] saved radio address is invalid - choose the radio again in Settings"); return }
        callbacks.onMeshLog("[mesh] connecting to ${device.name ?: address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun close() {
        val was = isConnected
        isConnected = false
        toRadio = null
        fromRadio = null
        reading = false
        readAgain = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        if (was) callbacks.onMeshDisconnected()
    }

    private fun reconnectLater() {
        close()
        if (!running) return
        failures++
        if (failures == 3) callbacks.onMeshLog("[mesh] can't reach the radio - is the Meshtastic app still connected to it? A radio takes one Bluetooth client at a time.")
        handler.postDelayed(retry, if (failures < 3) RETRY_MS else RETRY_SLOW_MS)
    }

    private fun requestNodes() {
        val g = gatt ?: return
        val c = toRadio ?: return
        // One GATT operation at a time: a write issued while a read is in flight is dropped.
        if (reading) { handler.postDelayed({ requestNodes() }, 1_000); return }
        val payload = MeshProto.wantConfig(MeshProto.NONCE_ONLY_NODES)
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") c.value = payload
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
        } catch (_: Exception) { false }
        if (!ok) callbacks.onMeshLog("[mesh] node request failed to send")
    }

    /** Reads FromRadio until it comes back empty (one GATT read in flight at a time). */
    private fun readNext() {
        val g = gatt ?: return
        val c = fromRadio ?: return
        if (reading) { readAgain = true; return }
        reading = true
        val ok = try { g.readCharacteristic(c) } catch (_: Exception) { false }
        if (!ok) { reading = false; handler.postDelayed({ readNext() }, 500) }
    }

    private val resync = object : Runnable {
        override fun run() {
            if (!isConnected) return
            requestNodes()
            handler.postDelayed(this, RESYNC_MS)
        }
    }

    private fun onFromRadio(value: ByteArray) {
        reading = false
        if (value.isEmpty()) {
            if (readAgain) { readAgain = false; readNext() }
            return
        }
        val msg = try { MeshProto.parseFromRadio(value) } catch (e: Exception) {
            callbacks.onMeshLog("[mesh] couldn't decode a message (${e.message})"); MeshProto.Message.Other
        }
        callbacks.onMeshMessage(msg)
        readNext()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (gatt !== g) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(512)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    callbacks.onMeshLog("[mesh] disconnected (status $status)")
                    reconnectLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { if (gatt === g) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (gatt !== g) return@post
                val s = g.getService(SERVICE_UUID)
                val to = s?.getCharacteristic(TORADIO_UUID)
                val from = s?.getCharacteristic(FROMRADIO_UUID)
                val num = s?.getCharacteristic(FROMNUM_UUID)
                val cccd = num?.getDescriptor(CCCD_UUID)
                if (to == null || from == null || num == null || cccd == null) {
                    callbacks.onMeshLog("[mesh] that device isn't a Meshtastic radio (no mesh service)")
                    running = false
                    close()
                    return@post
                }
                toRadio = to
                fromRadio = from
                g.setCharacteristicNotification(num, true)
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, enable)
                else { @Suppress("DEPRECATION") cccd.value = enable; @Suppress("DEPRECATION") g.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (gatt !== g || d.uuid != CCCD_UUID) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callbacks.onMeshLog("[mesh] subscribe failed ($status) - pair the radio with this phone first")
                    reconnectLater()
                    return@post
                }
                failures = 0
                isConnected = true
                callbacks.onMeshLog("[mesh] connected - reading the node list")
                callbacks.onMeshConnected()
                requestNodes()
                handler.postDelayed(resync, RESYNC_MS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (gatt === g && c.uuid == TORADIO_UUID) readNext() }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { if (gatt === g && c.uuid == FROMRADIO_UUID) onFromRadio(if (status == BluetoothGatt.GATT_SUCCESS) value else ByteArray(0)) }
        }

        @Deprecated("Pre-API-33 variant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value ?: ByteArray(0)
            handler.post { if (gatt === g && c.uuid == FROMRADIO_UUID) onFromRadio(if (status == BluetoothGatt.GATT_SUCCESS) v else ByteArray(0)) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { if (gatt === g && c.uuid == FROMNUM_UUID) readNext() }
        }

        @Deprecated("Pre-API-33 variant")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            handler.post { if (gatt === g && c.uuid == FROMNUM_UUID) readNext() }
        }
    }

    companion object {
        // meshtastic/firmware src/BluetoothCommon.h
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RETRY_MS = 10_000L
        private const val RETRY_SLOW_MS = 30_000L
        private const val RESYNC_MS = 5 * 60_000L
    }
}
