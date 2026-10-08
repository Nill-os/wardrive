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
    private var failures = 0
    private var loggedWaiting = false

    // One GATT operation at a time (Android drops an op issued while another is in flight):
    // writes queue here, and a read of FromRadio runs whenever no write is waiting.
    // Each queued write carries what it means for "the next NodeInfo is the radio's own":
    // true = a nodes-only want_config, false = any other want_config, null = no effect. It only
    // takes effect once the radio confirms that write (onCharacteristicWrite) - a read already in
    // flight still belongs to the stream the radio was sending before.
    private class Write(val payload: ByteArray, val armsOwnNode: Boolean?, val retried: Boolean = false)
    private val writes = ArrayDeque<Write>()
    private var inFlight: Write? = null
    private var busy = false
    private var wantRead = false
    // Set after a nodes-only request: the firmware sends the radio's own NodeInfo first
    // (PhoneAPI STATE_SEND_OWN_NODEINFO), and my_info isn't sent in that mode.
    private var awaitOwnNode = false

    @Volatile var isConnected = false
        private set

    /** The radio this link talks to (Settings can change mid-run; the link keeps its radio). */
    val radioAddress: String get() = address

    /** Every FromRadio message as read from the radio (main thread) - MeshBridgeServer forwards these. */
    var onFromRadioRaw: ((ByteArray) -> Unit)? = null

    /** True while the Meshtastic app is connected through MeshBridgeServer: it drives the radio
     *  (its own config requests and heartbeats), so this link stops sending requests of its own
     *  and just reads along. */
    var clientAttached = false
        set(value) {
            if (field == value) return
            field = value
            if (!value && isConnected) requestNodes() // back on our own - refresh the node list
        }

    fun start(radioAddress: String) {
        if (running) return
        address = radioAddress
        running = true
        failures = 0 // a fresh run starts with fast retries and the "is the Meshtastic app connected" hint
        loggedWaiting = false
        connect()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        close()
    }

    /** Queues a ToRadio message from the bridged Meshtastic app (main thread). */
    fun sendToRadio(payload: ByteArray) {
        if (!isConnected) return
        // want_config_id (field 3): a nodes-only request makes the radio send its own node first.
        val nonce = MeshProto.readWantConfig(payload)
        enqueueWrite(Write(payload, if (nonce < 0) null else nonce == MeshProto.NONCE_ONLY_NODES.toLong()))
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    private val retry = Runnable { connect() }

    private fun connect() {
        if (!running || gatt != null) return
        if (adapter == null || !adapter.isEnabled || !hasPermissions() || address.isEmpty()) {
            if (!loggedWaiting) {
                loggedWaiting = true
                callbacks.onMeshLog(if (!hasPermissions()) "[mesh] waiting for the Nearby devices (Bluetooth) permission"
                                    else "[mesh] waiting for Bluetooth to be turned on")
            }
            handler.postDelayed(retry, RETRY_MS)
            return
        }
        loggedWaiting = false
        val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) { callbacks.onMeshLog("[mesh] saved radio address is invalid - choose the radio again in Settings"); return }
        callbacks.onMeshLog("[mesh] connecting to ${device.name ?: address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) handler.postDelayed(retry, RETRY_MS) // stack refused - try again
    }

    private fun close() {
        val was = isConnected
        isConnected = false
        handler.removeCallbacks(resync)
        handler.removeCallbacks(retry)
        handler.removeCallbacks(pumpRetry)
        inFlight = null
        toRadio = null
        fromRadio = null
        writes.clear()
        busy = false
        wantRead = false
        awaitOwnNode = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        if (was) callbacks.onMeshDisconnected()
    }

    private fun reconnectLater() {
        close()
        if (!running) return
        failures++
        if (failures == 3) callbacks.onMeshLog("[mesh] can't reach the radio - is the Meshtastic app still connected to it over Bluetooth? A radio takes one Bluetooth connection at a time.")
        handler.postDelayed(retry, if (failures < 3) RETRY_MS else RETRY_SLOW_MS)
    }

    private fun requestNodes() {
        if (clientAttached) return
        enqueueWrite(Write(MeshProto.wantConfig(MeshProto.NONCE_ONLY_NODES), true))
    }

    private fun enqueueWrite(w: Write) {
        if (writes.size >= MAX_QUEUED_WRITES) { callbacks.onMeshLog("[mesh] radio write queue full - dropping a message"); return }
        writes.addLast(w)
        pump()
    }

    private val pumpRetry = Runnable { pump() }
    private fun retryPumpSoon() { handler.removeCallbacks(pumpRetry); handler.postDelayed(pumpRetry, 500) }

    /** Starts the next GATT operation if none is in flight: queued writes first, then a read. */
    private fun pump() {
        val g = gatt ?: return
        if (busy || !isConnected) return
        val next = writes.firstOrNull()
        if (next != null) {
            val c = toRadio ?: return
            busy = true
            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, next.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") c.value = next.payload
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
            } catch (_: Exception) { false }
            if (ok) { writes.removeFirst(); inFlight = next }
            else { busy = false; retryPumpSoon() } // stack busy - try again shortly
            return
        }
        if (wantRead) {
            val c = fromRadio ?: return
            busy = true
            wantRead = false
            val ok = try { g.readCharacteristic(c) } catch (_: Exception) { false }
            if (!ok) { busy = false; wantRead = true; retryPumpSoon() }
        }
    }

    private fun readSoon() { wantRead = true; pump() }

    private val resync = object : Runnable {
        override fun run() {
            if (!isConnected) return
            requestNodes()
            handler.postDelayed(this, RESYNC_MS)
        }
    }

    private fun onFromRadio(value: ByteArray, readFailed: Boolean = false) {
        busy = false
        // A failed read may have consumed the radio's own NodeInfo: never let the flag carry over
        // to whatever comes next (that would be someone else's node).
        if (readFailed) awaitOwnNode = false
        if (value.isEmpty()) { pump(); return } // radio's queue is drained - run any waiting write
        onFromRadioRaw?.invoke(value)
        var decodeFailed = false
        var msg = try { MeshProto.parseFromRadio(value) } catch (e: Exception) {
            decodeFailed = true
            callbacks.onMeshLog("[mesh] couldn't decode a message (${e.message})"); MeshProto.Message.Other
        }
        if (awaitOwnNode) {
            when {
                msg is MeshProto.Message.NodeInfo -> {
                    awaitOwnNode = false
                    // The firmware sends its own node without a hop count; anything else isn't it.
                    if (msg.node.hopsAway < 0) msg = MeshProto.Message.MyInfo(msg.node.num) // the radio itself, not a find
                }
                // An undecodable message may have been the own node; any other known message means
                // the moment has passed. (Other = e.g. a queue-status reply - keep waiting.)
                decodeFailed || msg !is MeshProto.Message.Other -> awaitOwnNode = false
            }
        }
        callbacks.onMeshMessage(msg)
        readSoon()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (gatt !== g) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(512)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    reconnectLater()
                    callbacks.onMeshLog("[mesh] disconnected (status $status)") // after close(), so the status refresh shows DOWN
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { if (gatt === g) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (gatt !== g) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { // transient (129/133 are common) - retry
                    callbacks.onMeshLog("[mesh] service discovery failed ($status) - retrying")
                    reconnectLater()
                    return@post
                }
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
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val subscribed = g.setCharacteristicNotification(num, true) && try {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
                    else { @Suppress("DEPRECATION") cccd.value = enable; @Suppress("DEPRECATION") g.writeDescriptor(cccd) }
                } catch (_: Exception) { false }
                if (!subscribed) { // no callback will come - don't sit half-connected for the whole run
                    callbacks.onMeshLog("[mesh] couldn't subscribe to the radio - reconnecting")
                    reconnectLater()
                }
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
                handler.removeCallbacks(resync)
                handler.postDelayed(resync, RESYNC_MS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (gatt !== g || c.uuid != TORADIO_UUID) return@post
                busy = false
                val done = inFlight
                inFlight = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callbacks.onMeshLog("[mesh] write to radio failed ($status)")
                    // Our own node-list request: try it once more rather than wait for the resync.
                    if (done != null && done.armsOwnNode == true && !done.retried && !clientAttached) {
                        writes.addFirst(Write(done.payload, true, retried = true))
                    }
                } else done?.armsOwnNode?.let { awaitOwnNode = it } // the radio now streams this request's answer
                readSoon() // the radio answers through FromRadio
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { if (gatt === g && c.uuid == FROMRADIO_UUID) onFromRadio(if (status == BluetoothGatt.GATT_SUCCESS) value else ByteArray(0), status != BluetoothGatt.GATT_SUCCESS) }
        }

        @Deprecated("Pre-API-33 variant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value ?: ByteArray(0)
            handler.post { if (gatt === g && c.uuid == FROMRADIO_UUID) onFromRadio(if (status == BluetoothGatt.GATT_SUCCESS) v else ByteArray(0), status != BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { if (gatt === g && c.uuid == FROMNUM_UUID) readSoon() }
        }

        @Deprecated("Pre-API-33 variant")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            handler.post { if (gatt === g && c.uuid == FROMNUM_UUID) readSoon() }
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
        private const val MAX_QUEUED_WRITES = 64
    }
}
