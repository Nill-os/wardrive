package com.dreknil.wardrivebridge

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Lets the official Meshtastic app use the radio while this app holds its Bluetooth link (a
 * Meshtastic radio takes one Bluetooth client at a time). During a run this serves the Meshtastic
 * network-radio protocol on 127.0.0.1:4403 - the app adds it under Connections -> Network as a
 * manual address - and relays it to the radio over MeshtasticRadioLink: ToRadio messages from the
 * app are written to the radio, and every FromRadio message read from the radio is sent back.
 * Both apps see every packet, and this app's mesh-node logging keeps working from the same stream.
 *
 * Wire format (meshtastic/firmware StreamAPI, Meshtastic-Android StreamFrameCodec): 0x94 0xC3,
 * big-endian 16-bit length, then a ToRadio/FromRadio protobuf of at most 512 bytes. Bound to the
 * loopback address only, so nothing off the phone can reach it; one client at a time.
 */
class MeshBridgeServer(private val link: MeshtasticRadioLink, private val log: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    /** Set once the client has sent its first message: only then is radio traffic forwarded to it,
     *  so it never receives the tail of a node-list read this app asked for before it connected. */
    @Volatile private var clientActive = false

    /** True while the Meshtastic app is connected through the bridge. */
    val hasClient: Boolean get() = client != null && clientActive

    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                // IPv4 127.0.0.1 explicitly: Android's getLoopbackAddress() is ::1, which an app
                // connecting to "127.0.0.1" can't reach.
                bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT), 1)
            }
        } catch (e: IOException) {
            log("[mesh] bridge couldn't open port $PORT (${e.message}) - is another app using it?")
            return
        }
        server = s
        log("[mesh] bridge ready - in the Meshtastic app, connect to 127.0.0.1")
        Thread({ acceptLoop(s) }, "mesh-bridge-accept").start()
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        dropClient()
    }

    /** Closes the Meshtastic app's connection (e.g. the radio went away), so it reconnects and
     *  asks for a fresh config once the radio is back. */
    fun dropClient() {
        val c = client ?: return
        client = null
        clientActive = false
        try { c.close() } catch (_: IOException) {}
        handler.post { link.clientAttached = false }
    }

    /** A FromRadio message just read from the radio (main thread). */
    fun forward(fromRadio: ByteArray) {
        val c = client ?: return
        if (!clientActive || fromRadio.size > MAX_PAYLOAD) return
        val frame = ByteArray(4 + fromRadio.size)
        frame[0] = START1; frame[1] = START2
        frame[2] = (fromRadio.size ushr 8).toByte(); frame[3] = fromRadio.size.toByte()
        fromRadio.copyInto(frame, 4)
        writer.execute {
            try { c.getOutputStream().apply { write(frame); flush() } } catch (_: IOException) { if (client === c) dropClient() }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { return }
            if (!link.isConnected) { // nothing to bridge to yet - let the app retry
                try { c.close() } catch (_: IOException) {}
                continue
            }
            dropClient() // one client at a time; a new connection replaces a stale one
            c.tcpNoDelay = true
            client = c
            handler.post { log("[mesh] Meshtastic app connected through the bridge") }
            Thread({ readLoop(c) }, "mesh-bridge-read").start()
        }
    }

    private fun readLoop(c: Socket) {
        val input: InputStream = try { c.getInputStream().buffered() } catch (_: IOException) { return }
        var state = 0
        var len = 0
        var got = 0
        val buf = ByteArray(MAX_PAYLOAD)
        try {
            while (true) {
                val b = input.read()
                if (b < 0) break
                val x = b.toByte()
                when (state) {
                    0 -> if (x == START1) state = 1 // anything else is wake bytes / noise
                    1 -> state = when (x) { START2 -> 2; START1 -> 1; else -> 0 }
                    2 -> { len = b shl 8; state = 3 }
                    3 -> {
                        len = len or b
                        got = 0
                        state = if (len in 1..MAX_PAYLOAD) 4 else 0
                    }
                    4 -> {
                        buf[got++] = x
                        if (got == len) {
                            val payload = buf.copyOf(len)
                            handler.post {
                                if (client !== c) return@post
                                clientActive = true
                                link.clientAttached = true
                                link.sendToRadio(payload)
                            }
                            state = 0
                        }
                    }
                }
            }
        } catch (_: IOException) {
        }
        if (client === c) {
            handler.post { log("[mesh] Meshtastic app disconnected from the bridge") }
            dropClient()
        }
    }

    companion object {
        const val PORT = 4403 // Meshtastic's network-radio port (StreamFrameCodec.DEFAULT_TCP_PORT)
        private const val START1 = 0x94.toByte()
        private const val START2 = 0xC3.toByte()
        private const val MAX_PAYLOAD = 512
    }
}
