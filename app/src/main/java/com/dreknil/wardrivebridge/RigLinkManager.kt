package com.dreknil.wardrivebridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Talks to cyd_node over BLE (primary, see RigBleLink) or USB serial (fallback) using the
 * "wdstream" line protocol the
 * ESP32 firmware speaks (same protocol WardriveGo's "Cerberus" mode reads -
 * see the firmware's cmd_wdstream-compatible WD:AP/WD:BLE/WD:STATUS lines).
 * Unlike WardriveGo, this also surfaces WD:BLE lines - WardriveGo's own
 * serial-read modes are documented APs-only, which is the whole reason this
 * app exists instead of just using WardriveGo directly.
 */
class RigLinkManager(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onRigConnected()
        fun onRigDisconnected()
        fun onRigObservation(obs: Observation)
        fun onRigStatus(rawLine: String)
        fun onRigLog(line: String)
        /** Rig's actual scanning state changed - from its physical button,
         * idle auto-stop, or in response to our own sendScanStart/Stop().
         * Not called from the main thread - route through runOnUiThread. */
        fun onRigScanStateChanged(active: Boolean)
        /** cyd_node's own mesh link to wifi_node changed - this is a THIRD link, distinct from
         * both onRigConnected/Disconnected (this phone's USB link to cyd_node) and
         * onRigScanStateChanged (whether the rig is actively scanning). The phone being
         * connected over USB says nothing about whether cyd_node can actually reach the rest of
         * the rig - a real, previously invisible gap. */
        fun onMeshLinkStateChanged(state: MeshLinkState)
    }

    /** Mirrors cyd_node's own LinkState enum exactly (see its main.cpp) - ordinal values must
     * stay in this order since WD:MESHLINK:<n> sends the raw ordinal. */
    enum class MeshLinkState { DISCONNECTED, CONNECTING, CONNECTED }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val lineBuffer = StringBuilder()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val handler = Handler(Looper.getMainLooper())

    // Which link is live. BLE is primary; USB is the fallback and only used while BLE isn't up -
    // when BLE connects it takes over from USB, and when BLE drops the USB retry loop takes over
    // again if a cable is plugged in. Only one is ever active, so each line arrives exactly once.
    private enum class Transport { NONE, USB, BLE }

    @Volatile
    private var transport = Transport.NONE
    private val identified get() = transport != Transport.NONE

    /** No rig paired yet - see RigBleLink's pairing notes. */
    val needsPairing: Boolean get() = ble.needsPairing

    fun forgetRig() = ble.forgetRig()

    /** "BLE" or "USB" while linked to cyd_node, null when not - for status displays. */
    val connectionType: String?
        get() = when (transport) {
            Transport.BLE -> "BLE"
            Transport.USB -> "USB"
            Transport.NONE -> null
        }

    // Set once checkIdentity() has confirmed the device on the USB port is really cyd_node (see
    // openDeviceWithDriver()'s comment) - false while a freshly opened port is still being
    // sniffed, or if it never gets there. Not needed for BLE: that link connects to cyd_node's
    // own service UUID, so it can't land on the wrong board.
    private var usbIdentified = false
    private val identifyBuffer = StringBuilder()

    private val bleLineBuffer = StringBuilder()
    private val ble = RigBleLink(context, object : RigBleLink.Callbacks {
        override fun onBleConnected() {
            // Main thread (RigBleLink posts it). Prefer BLE: drop a live USB link without
            // reporting a disconnect - the rig never went away, only the transport changed.
            val wasUp = identified
            if (port != null) closePort()
            handler.removeCallbacks(reconnectRetry)
            synchronized(bleLineBuffer) { bleLineBuffer.clear() }
            transport = Transport.BLE
            onTransportUp(announce = !wasUp)
        }

        override fun onBleDisconnected() {
            if (transport != Transport.BLE) return
            transport = Transport.NONE
            handler.removeCallbacks(wdstreamKeepAlive)
            wdstreamAcked = false
            listener.onRigDisconnected()
            retryUntilConnected() // USB fallback, if a cable's plugged in; BLE rescans on its own
        }

        override fun onBleData(data: ByteArray) {
            if (transport != Transport.BLE) return
            val text = String(data, Charsets.ISO_8859_1)
            val lines = mutableListOf<String>()
            synchronized(bleLineBuffer) {
                for (c in text) {
                    if (c == '\n') {
                        val line = bleLineBuffer.toString().trim()
                        bleLineBuffer.clear()
                        if (line.isNotEmpty()) lines.add(line)
                    } else if (c != '\r') {
                        bleLineBuffer.append(c)
                    }
                }
            }
            lines.forEach { processLine(it) }
        }

        override fun onBleLog(msg: String) = listener.onRigLog(msg)
    })

    /** A transport just became the live link: announce it and start the wdstream handshake. */
    private fun onTransportUp(announce: Boolean) {
        if (announce) listener.onRigConnected()
        wdstreamAcked = false
        handler.removeCallbacks(wdstreamKeepAlive)
        handler.post(wdstreamKeepAlive)
        handler.removeCallbacks(usbPresencePing)
        if (transport == Transport.USB) handler.postDelayed(usbPresencePing, USB_PRESENCE_PING_MS)
    }

    // Over USB, cyd_node has no connection state to tell whether a phone is listening, so its
    // PHONE indicator relies on hearing from the app - and after the handshake is acknowledged the
    // app otherwise goes quiet, which read as PHONE:DOWN a few seconds into every run while the
    // link was actually fine (2026-09-28). "wdstream status" is the harmless command to ping
    // with: unlike "wdstream start" it doesn't reset anything. Over BLE the connection itself is
    // the signal, so this only runs on USB.
    private val usbPresencePing = object : Runnable {
        override fun run() {
            if (transport != Transport.USB) return
            writeLine("wdstream status", log = false)
            handler.postDelayed(this, USB_PRESENCE_PING_MS)
        }
    }

    // Real-world evidence (a full drive, ~3000 real rows landed on the
    // rig's own SD card while the phone showed zero the entire time) points
    // at the one-shot "wdstream start" sent right after opening the port
    // getting silently dropped - these boards have no separate USB-to-UART
    // bridge chip, only the ESP32-S3's native USB CDC peripheral, which can
    // plausibly not be ready to receive a write in the first instant after
    // the host opens it. Rather than chase the exact race, make the
    // handshake self-healing by resending "wdstream start" until we see the
    // rig actually respond (any WD:BEGIN/STATUS/AP/BLE line - see
    // processLine()), then stop for good on this connection.
    //
    // This used to keep resending on a fixed timer for as long as the port
    // stayed open, with no way to tell it already got through. Since
    // "wdstream start" is idempotent on the firmware side - it resets its
    // own counters and re-prints WD:BEGIN - that meant a real, connected
    // rig had its WiFi/BLE counts wiped and restarted every
    // WDSTREAM_RESEND_MS for the entire run: confirmed live via the phone
    // app's Terminal tab, which showed uptime never climbing past ~4s and
    // aps=0/bles=0 forever on a rig that had been plugged in and scanning
    // for over a minute (found while porting this same code into the
    // Organic Maps integration, 2026-09-27; the bug is identical here).
    // Stopping once acknowledged keeps the "recover from a dropped first
    // attempt" self-healing this was built for, without also resetting an
    // already-working stream every few seconds.
    private var wdstreamAcked = false
    private val wdstreamKeepAlive = object : Runnable {
        override fun run() {
            if (transport == Transport.NONE || wdstreamAcked) return
            writeLine("wdstream start")
            handler.postDelayed(this, WDSTREAM_RESEND_MS)
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                openDevice(device)
            } else {
                listener.onRigLog("[rig] USB permission denied")
            }
        }
    }

    private var receiverRegistered = false

    // Retries findAndConnect() periodically so the phone reconnects on its own instead of
    // needing a manual unplug/replug or a tap on "Connect Rig" - confirmed for real: a run that
    // lost the rig mid-drive never reconnected on its own before this (2026-09-28). Keyed off
    // !identified rather than port == null so it covers every way a connection attempt can fail
    // to reach a genuinely-confirmed link, not just "no USB device found": a misidentified board
    // (wrong USB port) hits identifyTimeout's closePort() and would otherwise sit disconnected
    // forever with nothing scheduled to retry it once the right board gets plugged in later, same
    // for the plain "nothing plugged in yet" case at start() - only the mid-session I/O-error
    // drop used to get a retry loop (2026-09-28), every other failure to connect did not
    // (2026-09-28). findAndConnect()'s own port != null re-entry guard makes a tick that lands
    // mid-identification a harmless no-op rather than a duplicate attempt.
    private val reconnectRetry = object : Runnable {
        override fun run() {
            findAndConnect()
            if (!identified) handler.postDelayed(this, RECONNECT_RETRY_MS)
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
        ble.start()
        retryUntilConnected()
    }

    /** Entry point for a manual "Connect Rig" tap or a fresh USB_DEVICE_ATTACHED intent - unlike
     * calling findAndConnect() directly, this also (re)arms the persistent retry loop, so a tap
     * that doesn't immediately succeed (permission dialog still pending, wrong board plugged in)
     * still ends up connected on its own once the real board is in place, instead of needing
     * another manual tap. */
    fun retryUntilConnected() {
        handler.removeCallbacks(reconnectRetry)
        reconnectRetry.run()
    }

    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(permissionReceiver)
            receiverRegistered = false
        }
        handler.removeCallbacks(reconnectRetry)
        handler.removeCallbacks(usbPresencePing)
        handler.removeCallbacks(wdstreamKeepAlive)
        transport = Transport.NONE
        ble.stop()
        closePort()
    }

    /** Call when a USB_DEVICE_ATTACHED intent arrives, or to retry discovery.
     * Guarded against re-entry: this can legitimately be called from three
     * independent places that can all fire close together (RigLinkManager's
     * own start(), the "Connect Rig" button, and onNewIntent() when Android
     * delivers a fresh USB_DEVICE_ATTACHED broadcast) - without this guard,
     * two overlapping calls can each open their own UsbDeviceConnection to
     * the same device, with the second one's open silently invalidating the
     * first's, which then throws "Connection closed" on its very next read
     * and gets torn back down - a connect/disconnect/reconnect flap that
     * looks like "connected" on screen but never stays up long enough for
     * any real data (including the wdstream handshake) to get through. */
    private var reportedNoUsbDevice = false

    fun findAndConnect() {
        if (port != null) return // already connected - nothing to do
        if (transport == Transport.BLE) return // BLE is primary - USB only fills in while it's down
        val driver: UsbSerialDriver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull() ?: run {
                // Once per absence, not on every retry - it used to flood the terminal.
                if (!reportedNoUsbDevice) {
                    reportedNoUsbDevice = true
                    listener.onRigLog("[rig] no USB cable to the CYD - using Bluetooth")
                }
                return
            }
        reportedNoUsbDevice = false

        val device = driver.device
        if (usbManager.hasPermission(device)) {
            openDeviceWithDriver(driver)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        // Android 14+ refuses to create a MUTABLE PendingIntent around an
        // implicit intent (no target package) - crashes the whole app the
        // instant permission needs to be (re-)requested, e.g. the very
        // first time the rig is plugged in after a fresh install. Setting
        // the package explicitly targets it at our own registered receiver
        // and makes it an explicit intent, which sidesteps the restriction
        // entirely instead of needing the "unsafe implicit intent" opt-in.
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            listener.onRigLog("[rig] device not recognized as a serial device")
            return
        }
        openDeviceWithDriver(driver)
    }

    private fun openDeviceWithDriver(driver: UsbSerialDriver) {
        // findAndConnect()'s own re-entry guard doesn't cover this function's
        // other caller (openDevice(), from the USB-permission-granted
        // broadcast) - guarding here instead covers every path that can
        // reach an open attempt, not just one of them.
        if (port != null) return
        val connection = usbManager.openDevice(driver.device) ?: run {
            listener.onRigLog("[rig] could not open USB connection")
            return
        }
        val serialPort = driver.ports[0]
        try {
            serialPort.open(connection)
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // The ESP32-S3's native USB CDC (TinyUSB) only drains its RX FIFO
            // while it sees DTR asserted - a plain terminal (screen, pyserial,
            // the Arduino monitor) always raises DTR on open, which is why the
            // rig talks fine over USB on a dev machine but silently swallowed
            // every byte the phone wrote: this library doesn't raise DTR/RTS
            // on open() by itself, so without this, TX from the phone reaches
            // the device's USB endpoint but the firmware never sees it as
            // available to read.
            try {
                serialPort.dtr = true
                serialPort.rts = true
            } catch (e: Exception) {
                listener.onRigLog("[rig] DTR/RTS not supported: ${e.message}")
            }
        } catch (e: Exception) {
            listener.onRigLog("[rig] failed to open port: ${e.message}")
            return
        }
        port = serialPort
        usbIdentified = false
        identifyBuffer.clear()

        val manager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (usbIdentified) {
                    if (transport == Transport.USB) handleIncoming(data)
                } else {
                    checkIdentity(data)
                }
            }

            override fun onRunError(e: Exception) {
                listener.onRigLog("[rig] link error: ${e.message}")
                val wasActive = transport == Transport.USB
                closePort()
                if (wasActive) {
                    transport = Transport.NONE
                    listener.onRigDisconnected()
                }
                handler.removeCallbacks(reconnectRetry)
                handler.postDelayed(reconnectRetry, RECONNECT_RETRY_MS)
            }
        })
        ioManager = manager
        ioExecutor.submit(manager)

        // The rig has three identical-looking ESP32 USB ports (wifi_node, ble_node, cyd_node) with
        // no distinguishing USB VID/PID, so a misplugged OTG cable looks exactly like a normal
        // connection at the USB level - real-world evidence this session: the phone silently read
        // a non-cyd_node board's own debug output ("[link] rx: SDOK:1", "sent=") for several
        // minutes with "Rig: connected" showing the whole time, and wdstream never worked because
        // only cyd_node implements the phone-facing wdstream protocol (wifi_node/ble_node do not -
        // they only talk to each other and to cyd_node over their own inter-board links; see
        // cyd_node/main.cpp's "WardriveGo wdstream compatibility" section and wifi_node/main.cpp's
        // own header, which says plainly "cyd_node is the one the phone plugs into now"). Don't
        // call onRigConnected() until a byte pattern unique to cyd_node's own heartbeat line
        // ("sdOk=", only cyd_node prints this) has actually been seen.
        handler.postDelayed(identifyTimeout, IDENTIFY_TIMEOUT_MS)
    }

    private fun checkIdentity(data: ByteArray) {
        identifyBuffer.append(String(data, Charsets.ISO_8859_1))
        if (identifyBuffer.contains("sdOk=")) {
            usbIdentified = true
            identifyBuffer.clear()
            // IO thread - hop to main so transport changes stay single-threaded with the BLE side.
            handler.post {
                handler.removeCallbacks(identifyTimeout)
                if (transport == Transport.BLE) {
                    closePort() // BLE won the race - it's primary
                    return@post
                }
                handler.removeCallbacks(reconnectRetry)
                transport = Transport.USB
                // Ask the rig to start streaming - see cyd_node's handleWdstreamCommand().
                // Resent by wdstreamKeepAlive until processLine() sees the rig actually respond.
                onTransportUp(announce = true)
            }
            return
        }
        // Cap so a device that never prints "sdOk=" (garbage, or a firmware
        // built without WARDRIVE_DEBUG) doesn't grow this without bound
        // while waiting for identifyTimeout to fire.
        if (identifyBuffer.length > 8000) identifyBuffer.delete(0, identifyBuffer.length - 4000)
    }

    private val identifyTimeout = Runnable {
        if (usbIdentified) return@Runnable
        val sawWrongBoard = identifyBuffer.contains("[link] rx:") || identifyBuffer.contains("SDOK:")
        listener.onRigLog(
            if (sawWrongBoard)
                "[rig] misplug: this is wifi_node or ble_node, not the CYD display board - move the " +
                    "phone's USB-OTG cable to the CYD's USB port"
            else
                "[rig] connected but couldn't identify the CYD display board - check the cable and " +
                    "that its firmware is flashed with debug output enabled"
        )
        closePort()
    }

    /** Ask the rig to start scanning, same effect as its physical button -
     * see cyd_node's "scan start" handler. */
    fun sendScanStart() = writeLine("scan start")

    /** Ask the rig to stop scanning - see cyd_node's "scan stop" handler. */
    fun sendScanStop() = writeLine("scan stop")

    private fun writeLine(line: String, log: Boolean = true) {
        when (transport) {
            Transport.BLE -> ble.writeLine(line)
            Transport.USB -> {
                val activePort = port ?: return
                try {
                    activePort.write((line + "\n").toByteArray(), 1000)
                } catch (e: Exception) {
                    listener.onRigLog("[rig] write failed: ${e.message}")
                    return
                }
            }
            Transport.NONE -> return // nothing connected - don't claim a TX that never happened
        }
        if (log) listener.onRigLog("TX: $line")
    }

    /** Tears down the USB port only - the caller decides whether that ends the active link. */
    private fun closePort() {
        handler.removeCallbacks(identifyTimeout)
        if (transport == Transport.USB) {
            handler.removeCallbacks(wdstreamKeepAlive)
            handler.removeCallbacks(usbPresencePing)
            wdstreamAcked = false
        }
        usbIdentified = false
        identifyBuffer.clear()
        lineBuffer.clear()
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
    }

    private fun handleIncoming(data: ByteArray) {
        val text = String(data, Charsets.ISO_8859_1) // raw bytes, no encoding assumptions yet
        for (c in text) {
            if (c == '\n') {
                val line = lineBuffer.toString().trim()
                lineBuffer.clear()
                if (line.isNotEmpty()) processLine(line)
            } else if (c != '\r') {
                lineBuffer.append(c)
            }
        }
    }

    private fun processLine(line: String) {
        if (!line.startsWith("WD:")) return // ignore the rig's own debug prints
        listener.onRigLog("RX: $line")

        // Any WD: line at all proves the rig received and is acting on the "wdstream start" we
        // sent - stop the keepalive's periodic resend so it doesn't keep wiping the rig's own
        // WiFi/BLE counters by re-issuing that (idempotent-but-resetting) command every few
        // seconds for the rest of the run.
        if (!wdstreamAcked) {
            wdstreamAcked = true
            handler.removeCallbacks(wdstreamKeepAlive)
        }

        when {
            line.startsWith("WD:AP ") -> parseApLine(line)
            line.startsWith("WD:BLE ") -> parseBleLine(line)
            line.startsWith("WD:SCANSTATE:") ->
                listener.onRigScanStateChanged(line.substring("WD:SCANSTATE:".length).trim() == "1")
            line.startsWith("WD:MESHLINK:") -> {
                val ordinal = line.substring("WD:MESHLINK:".length).trim().toIntOrNull()
                val state = ordinal?.let { MeshLinkState.entries.getOrNull(it) }
                if (state != null) listener.onMeshLinkStateChanged(state)
            }
            line.startsWith("WD:STATUS") || line.startsWith("WD:BEGIN") || line.startsWith("WD:END") ->
                listener.onRigStatus(line)
        }
    }

    // "WD:AP ts=... bssid=AA:BB:.. ssid_hex=<hex> rssi=-60 ch=6 auth=WPA2 hidden=0"
    private fun parseApLine(line: String) {
        val fields = parseFields(line)
        val bssid = fields["bssid"] ?: return
        val ssid = hexDecode(fields["ssid_hex"] ?: "")
        val rssi = fields["rssi"]?.toIntOrNull() ?: 0
        val channel = fields["ch"]?.toIntOrNull() ?: 0
        val auth = fields["auth"] ?: "OPEN"
        val hidden = fields["hidden"] == "1"

        listener.onRigObservation(
            Observation(
                source = Source.RIG_WIFI,
                mac = bssid,
                label = ssid,
                authOrType = auth,
                channel = channel,
                frequencyMHz = channelToFreqMHz(channel),
                rssi = rssi,
                lat = 0.0, lon = 0.0, altitudeM = 0.0, accuracyM = 0.0, // filled in by MainActivity from phone GPS
                firstSeenIso = isoFormat.format(Date()),
                timestampMs = System.currentTimeMillis(),
                hidden = hidden,
                isPineapple = DeviceSignatureDetection.isPineapple(ssid),
            )
        )
    }

    // "WD:BLE ts=... mac=AA:BB:.. name_hex=<hex> rssi=-70 type=adv mfg=<hex>"
    // mfg is the raw manufacturer-data AD structure ble_node relayed - a
    // 2-byte little-endian company ID followed by that manufacturer's own
    // payload - or empty if the advertisement carried none.
    private fun parseBleLine(line: String) {
        val fields = parseFields(line)
        val mac = fields["mac"] ?: return
        val name = hexDecode(fields["name_hex"] ?: "")
        val rssi = fields["rssi"]?.toIntOrNull() ?: 0
        val mfgBytes = hexDecodeBytes(fields["mfg"] ?: "")
        val companyId = mfgCompanyId(mfgBytes)
        val tracker = isTrackerFromMfgBytes(mfgBytes)
        val flipper = DeviceSignatureDetection.isFlipperZero(mac, name)
        val flock = DeviceSignatureDetection.isFlockSafety(mac, name, companyId)
        val skimmer = DeviceSignatureDetection.isSkimmer(name)
        // isDrone/isMeshRadio need service UUIDs, which this line format
        // doesn't carry (see Observation.isDrone's own comment) - name-based
        // signatures only here, same set PhoneBleScanner can also derive from
        // just a name.
        val adultToy = DeviceSignatureDetection.isAdultToy(name)
        val glasses = DeviceSignatureDetection.isGlasses(name)
        val actionCam = DeviceSignatureDetection.isActionCam(name)
        val policeCam = DeviceSignatureDetection.isPoliceCam(name)

        listener.onRigObservation(
            Observation(
                source = Source.RIG_BLE,
                mac = mac,
                label = if (tracker && name.isBlank()) "(possible tracker)" else name,
                authOrType = "BLE",
                channel = 0,
                frequencyMHz = 0,
                rssi = rssi,
                lat = 0.0, lon = 0.0, altitudeM = 0.0, accuracyM = 0.0,
                firstSeenIso = isoFormat.format(Date()),
                timestampMs = System.currentTimeMillis(),
                isTracker = tracker,
                isFlipperZero = flipper,
                isFlockCamera = flock,
                isSkimmer = skimmer,
                isAdultToy = adultToy,
                isGlasses = glasses,
                isActionCam = actionCam,
                isPoliceCam = policeCam,
                companyId = companyId,
            )
        )
    }

    private fun mfgCompanyId(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }

    private fun isTrackerFromMfgBytes(bytes: ByteArray): Boolean {
        val companyId = mfgCompanyId(bytes) ?: return false
        val payload = bytes.copyOfRange(2, bytes.size)
        return TrackerDetection.isTracker(companyId, payload)
    }

    private fun hexDecodeBytes(hex: String): ByteArray {
        if (hex.isEmpty() || hex.length % 2 != 0) return ByteArray(0)
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun parseFields(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        // Skip "WD:AP " / "WD:BLE " token, then split "key=value" pairs on spaces.
        val body = line.substringAfter(' ')
        for (token in body.split(' ')) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            out[token.substring(0, eq)] = token.substring(eq + 1)
        }
        return out
    }

    private fun hexDecode(hex: String): String {
        val bytes = hexDecodeBytes(hex)
        if (bytes.isEmpty() && hex.isNotEmpty()) return "" // hex was malformed, not just empty input
        return String(bytes, Charsets.UTF_8)
    }

    private fun channelToFreqMHz(channel: Int): Int = when {
        channel in 1..13 -> 2407 + channel * 5
        channel == 14 -> 2484
        else -> 0
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.dreknil.wardrivebridge.USB_PERMISSION"
        private const val WDSTREAM_RESEND_MS = 5000L
        // cyd_node's heartbeat fires roughly every 2s, so this gives it two
        // full cycles to prove its identity before giving up.
        private const val IDENTIFY_TIMEOUT_MS = 4500L
        private const val RECONNECT_RETRY_MS = 4000L
        private const val USB_PRESENCE_PING_MS = 5000L // well inside cyd_node's 12s PHONE timeout
    }
}

/** Intent.getParcelableExtra() is deprecated (with a replacement) starting
 * API 33 but the replacement doesn't exist on older APIs - this picks
 * whichever is available at runtime. */
@Suppress("DEPRECATION")
private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, UsbDevice::class.java) as? T
    } else {
        getParcelableExtra(key)
    }
}
