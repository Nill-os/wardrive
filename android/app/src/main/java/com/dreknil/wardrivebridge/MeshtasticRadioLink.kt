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
 * ToRadio{want_config_id = 69420 (config only - starts with my_info, the radio's own node number)}
 * and {want_config_id = 69421 (nodes only)}, then read FromRadio until it comes back empty;
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
    private var failedReads = 0
    // A GATT op that never calls back (an Android stack quirk) would leave busy set forever.
    private val setupTimeout = Runnable {
        if (gatt != null && !isConnected) { callbacks.onMeshLog("[mesh] connection setup timed out - retrying"); reconnectLater() }
    }
    private val opWatchdog = Runnable {
        if (busy && isConnected) { callbacks.onMeshLog("[mesh] radio stopped answering - reconnecting"); reconnectLater() }
    }

    // One GATT operation at a time (Android drops an op issued while another is in flight):
    // writes queue here, and a read of FromRadio runs whenever no write is waiting.
    // ours = this app's own config request (retried once if the write fails).
    private class Write(val payload: ByteArray, val ours: Boolean = false, val retried: Boolean = false) {
        var heartbeatAdded = false // at most one heartbeat put in front of it (a failing link can't loop)
    }
    private val writes = ArrayDeque<Write>()
    private var inFlight: Write? = null
    private var lastAccepted: ByteArray? = null // the last ToRadio the radio took (it drops an identical next one)
    private var busy = false
    private var wantRead = false
    // True once the radio has sent my_info on this connection. Until then each node-list request
    // is preceded by a config-only request (whose stream starts with my_info), and ScanService
    // logs no finds - so the user's own radio is known by number before any node is considered.
    private var gotMyInfo = false
    private var nodesReadOnce = false // a nodes-only stream completed on this connection
    // The radio queues every packet it receives - with or without an app connected - and hands the
    // queue over after a config stream: packets read before the queue first runs empty may be old.
    private var drainingBacklog = true
    private var myInfoAttempts = 0
    private var opStartFailures = 0

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
        enqueueWrite(Write(payload))
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
        if (gatt == null) { handler.postDelayed(retry, RETRY_MS); return } // stack refused - try again
        // Connect -> MTU -> discovery -> subscribe must finish; a missing callback anywhere would
        // otherwise leave the link half-open (and DOWN) for the rest of the run.
        handler.removeCallbacks(setupTimeout)
        handler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
    }

    private fun close() {
        val was = isConnected
        isConnected = false
        handler.removeCallbacks(resync)
        handler.removeCallbacks(retry)
        handler.removeCallbacks(pumpRetry)
        handler.removeCallbacks(opWatchdog)
        lastAccepted = null // the firmware forgets it on disconnect too
        handler.removeCallbacks(setupTimeout)
        failedReads = 0
        gotMyInfo = false
        nodesReadOnce = false
        drainingBacklog = true
        handler.removeCallbacks(syncCheck)
        myInfoAttempts = 0
        opStartFailures = 0
        inFlight = null
        toRadio = null
        fromRadio = null
        writes.clear()
        busy = false
        wantRead = false
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

    /** Asks for the node list - preceded, until the radio has identified itself, by a config-only
     *  request; the node-list request then follows from that stream's config_complete. (Not both at
     *  once: a new want_config restarts the radio's stream, so queuing them back to back threw away
     *  the config stream - and its my_info - before it was read.) */
    private fun requestNodes() {
        if (clientAttached) return
        val nonce = if (gotMyInfo) MeshProto.NONCE_ONLY_NODES else MeshProto.NONCE_ONLY_CONFIG
        val request = MeshProto.wantConfig(nonce)
        // The firmware silently drops a ToRadio identical to the last one it accepted (NimBLE and
        // nRF52 "Drop dup ToRadio packet" - seen on the user's radio), which made every resync and
        // retry a no-op. A heartbeat in between makes the request new again.
        val previous = writes.lastOrNull()?.payload ?: inFlight?.payload ?: lastAccepted
        if (previous != null && previous.contentEquals(request)) enqueueWrite(Write(MeshProto.HEARTBEAT, ours = true))
        enqueueWrite(Write(request, ours = true))
        // Until the radio has identified itself and a node list has come back on this connection
        // (syncPending), re-ask after SYNC_CHECK_MS without stream data - re-armed by each
        // non-packet message - rather than wait for the 5-min resync.
        if (syncPending()) { handler.removeCallbacks(syncCheck); handler.postDelayed(syncCheck, SYNC_CHECK_MS) }
    }

    // Still waiting for this connection's identification or first node list.
    private fun syncPending() = !nodesReadOnce || !gotMyInfo
    private val syncCheck = Runnable { if (isConnected && syncPending() && !clientAttached) requestNodes() }

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
                    g.writeCharacteristic(c, next.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") c.value = next.payload
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
            } catch (_: Exception) { false }
            if (ok) { writes.removeFirst(); inFlight = next; opStartFailures = 0; armWatchdog() }
            else if (++opStartFailures > MAX_OP_START_FAILURES) { // the stack keeps refusing - start over
                callbacks.onMeshLog("[mesh] the radio connection refuses writes - reconnecting")
                reconnectLater()
            } else { busy = false; retryPumpSoon() } // stack busy - try again shortly
            return
        }
        if (wantRead) {
            val c = fromRadio ?: return
            busy = true
            wantRead = false
            val ok = try { g.readCharacteristic(c) } catch (_: Exception) { false }
            if (ok) { opStartFailures = 0; armWatchdog() }
            else if (++opStartFailures > MAX_OP_START_FAILURES) {
                callbacks.onMeshLog("[mesh] the radio connection refuses reads - reconnecting")
                reconnectLater()
            } else { busy = false; wantRead = true; retryPumpSoon() }
        }
    }

    private fun armWatchdog() { handler.removeCallbacks(opWatchdog); handler.postDelayed(opWatchdog, OP_TIMEOUT_MS) }

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
        if (readFailed) {
            // Read the rest of the stream rather than wait for the next FromNum notify (which may
            // not come on a quiet mesh); a read that keeps failing means a broken link - reconnect.
            if (++failedReads <= MAX_FAILED_READS) { wantRead = true; retryPumpSoon(); return }
            callbacks.onMeshLog("[mesh] reads from the radio keep failing - reconnecting")
            reconnectLater()
            return
        }
        failedReads = 0
        if (value.isEmpty()) { drainingBacklog = false; pump(); return } // radio's queue is drained - run any waiting write
        onFromRadioRaw?.invoke(value)
        var msg = try { MeshProto.parseFromRadio(value) } catch (e: Exception) {
            callbacks.onMeshLog("[mesh] couldn't decode a message (${e.message})"); MeshProto.Message.Other
        }
        if (drainingBacklog && msg is MeshProto.Message.Heard) msg = msg.copy(backlog = true)
        if (msg is MeshProto.Message.MyInfo && msg.nodeNum != 0L) gotMyInfo = true
        if (msg is MeshProto.Message.ConfigComplete) {
            failures = 0 // a working connection - back to fast retries if it drops
            // What follows any config stream (ours or the bridged app's) is the radio's queue - and on
            // 2.8+ firmware a replay of cached packets for every known node, shaped like live ones -
            // so it counts as backlog until the next empty read.
            drainingBacklog = true
        }
        if (msg is MeshProto.Message.ConfigComplete && msg.id == MeshProto.NONCE_ONLY_NODES.toLong()) {
            nodesReadOnce = true
            if (!syncPending()) handler.removeCallbacks(syncCheck)
        } else if (syncPending() && msg !is MeshProto.Message.Heard && msg !is MeshProto.Message.UnreadablePacket) {
            // Inactivity, not a deadline: a big node list can take longer than SYNC_CHECK_MS to
            // read, and re-asking mid-stream would restart it from the top every time. Live
            // packets don't count as progress - they keep coming after a lost config_complete.
            handler.removeCallbacks(syncCheck)
            handler.postDelayed(syncCheck, SYNC_CHECK_MS)
        }
        if (msg is MeshProto.Message.ConfigComplete && !clientAttached) {
            when {
                gotMyInfo && msg.id == MeshProto.NONCE_ONLY_CONFIG.toLong() -> requestNodes() // identified - now the nodes
                !gotMyInfo && ++myInfoAttempts <= 3 -> requestNodes() // my_info was lost (failed read) - ask again
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
                    if (!g.requestMtu(512) && !g.discoverServices()) reconnectLater()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    reconnectLater()
                    callbacks.onMeshLog("[mesh] disconnected (status $status)") // after close(), so the status refresh shows DOWN
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { if (gatt === g && !g.discoverServices()) reconnectLater() }
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
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, enable) == android.bluetooth.BluetoothStatusCodes.SUCCESS
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
                handler.removeCallbacks(setupTimeout) // (failures resets on the first completed config stream)
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
                if (status == BluetoothGatt.GATT_SUCCESS && done != null) lastAccepted = done.payload
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callbacks.onMeshLog("[mesh] write to radio failed ($status)")
                    // Our own config request: try it once more rather than wait for the resync.
                    if (done != null && done.ours && !clientAttached) {
                        if (!done.retried) writes.addFirst(Write(done.payload, ours = true, retried = true))
                        else { reconnectLater(); return@post } // failed twice - start the connection over
                    }
                    // After any failed write, the next one may now repeat the radio's last accepted
                    // write (which it would silently drop) - put a heartbeat in front if so.
                    writes.firstOrNull()?.let { next ->
                        if (!next.heartbeatAdded && next.payload.contentEquals(lastAccepted) && !next.payload.contentEquals(MeshProto.HEARTBEAT)) {
                            next.heartbeatAdded = true
                            writes.addFirst(Write(MeshProto.HEARTBEAT, ours = true))
                        }
                    }
                }
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
        private const val MAX_FAILED_READS = 2
        private const val MAX_OP_START_FAILURES = 10
        private const val SETUP_TIMEOUT_MS = 30_000L
        private const val SYNC_CHECK_MS = 30_000L
        private const val OP_TIMEOUT_MS = 10_000L
    }
}
