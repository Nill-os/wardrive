package com.dreknil.wardrivebridge

/**
 * Just enough protobuf to read a Meshtastic radio's node database over its Bluetooth API - no
 * protobuf library, no generated code. Field numbers are from meshtastic/protobufs
 * meshtastic/mesh.proto and portnums.proto (checked 2026-10-07):
 *
 *   ToRadio    { want_config_id = 3 (uint32) }
 *   FromRadio  { packet = 2 (MeshPacket), my_info = 3 (MyNodeInfo), node_info = 4 (NodeInfo),
 *                config_complete_id = 7 (uint32) }          - everything else is skipped
 *   MyNodeInfo { my_node_num = 1 }
 *   NodeInfo   { num = 1, user = 2, position = 3, snr = 4 (float), last_heard = 5 (fixed32),
 *                via_mqtt = 8, hops_away = 9 }
 *   User       { id = 1 ("!a1b2c3d4"), long_name = 2, short_name = 3, hw_model = 5 }
 *   Position   { latitude_i = 1 (sfixed32, deg * 1e7), longitude_i = 2, altitude = 3, time = 4 }
 *   MeshPacket { from = 1 (fixed32), decoded = 4 (Data), rx_snr = 8 (float), rx_rssi = 12,
 *                via_mqtt = 14 }
 *   Data       { portnum = 1, payload = 2 }                 POSITION_APP = 3, NODEINFO_APP = 4
 */
object MeshProto {
    /** firmware PhoneAPI.h SPECIAL_NONCE_ONLY_NODES: "send me the node database, skip the config". */
    const val NONCE_ONLY_NODES = 69421
    private const val PORT_POSITION = 3
    private const val PORT_NODEINFO = 4

    data class Position(val lat: Double, val lon: Double, val altitudeM: Int, val time: Long)
    data class User(val id: String, val longName: String, val shortName: String, val hwModel: Int)
    data class Node(
        val num: Long,
        val user: User? = null,
        val position: Position? = null,
        val snr: Float = 0f,
        val lastHeard: Long = 0,
        val hopsAway: Int = -1,
        val viaMqtt: Boolean = false,
    ) {
        /** Meshtastic's own "!a1b2c3d4" id (WDGWars strips the "!"). */
        val nodeId: String get() = user?.id?.takeIf { it.length >= 8 } ?: "!%08x".format(num)
    }

    sealed class Message {
        data class NodeInfo(val node: Node) : Message()
        data class MyInfo(val nodeNum: Long) : Message()
        data class ConfigComplete(val id: Long) : Message()
        /** A live packet: a position or node-info broadcast just heard over the radio. */
        data class Heard(val from: Long, val position: Position?, val user: User?, val snr: Float,
                         val rssi: Int, val viaMqtt: Boolean) : Message()
        object Other : Message()
    }

    fun wantConfig(nonce: Int): ByteArray = byteArrayOf(0x18) + varint(nonce.toLong() and 0xffffffffL) // field 3, varint

    fun parseFromRadio(b: ByteArray): Message {
        val r = Reader(b)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> return parsePacket(r.bytes()) ?: Message.Other
                field == 3 && wire == 2 -> return Message.MyInfo(parseMyInfo(r.bytes()))
                field == 4 && wire == 2 -> return Message.NodeInfo(parseNode(r.bytes()))
                field == 7 && wire == 0 -> return Message.ConfigComplete(r.varint())
                else -> r.skip(wire)
            }
        }
        return Message.Other
    }

    private fun parseMyInfo(b: ByteArray): Long {
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            if (f == 1 && w == 0) return r.varint() and 0xffffffffL else r.skip(w)
        }
        return 0
    }

    fun parseNode(b: ByteArray): Node {
        var node = Node(0)
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            node = when {
                f == 1 && w == 0 -> node.copy(num = r.varint() and 0xffffffffL)
                f == 2 && w == 2 -> node.copy(user = parseUser(r.bytes()))
                f == 3 && w == 2 -> node.copy(position = parsePosition(r.bytes()))
                f == 4 && w == 5 -> node.copy(snr = Float.fromBits(r.fixed32()))
                f == 5 && w == 5 -> node.copy(lastHeard = r.fixed32().toLong() and 0xffffffffL)
                f == 8 && w == 0 -> node.copy(viaMqtt = r.varint() != 0L)
                f == 9 && w == 0 -> node.copy(hopsAway = r.varint().toInt())
                else -> { r.skip(w); node }
            }
        }
        return node
    }

    private fun parseUser(b: ByteArray): User {
        var id = ""; var longName = ""; var shortName = ""; var hw = 0
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 2 -> id = r.string()
                f == 2 && w == 2 -> longName = r.string()
                f == 3 && w == 2 -> shortName = r.string()
                f == 5 && w == 0 -> hw = r.varint().toInt()
                else -> r.skip(w)
            }
        }
        return User(id, longName, shortName, hw)
    }

    /** null when the message carries no position (both coordinates missing or 0). */
    private fun parsePosition(b: ByteArray): Position? {
        var latI = 0; var lonI = 0; var alt = 0; var time = 0L
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 5 -> latI = r.fixed32()
                f == 2 && w == 5 -> lonI = r.fixed32()
                f == 3 && w == 0 -> alt = r.varint().toInt()
                f == 4 && w == 5 -> time = r.fixed32().toLong() and 0xffffffffL
                else -> r.skip(w)
            }
        }
        if (latI == 0 && lonI == 0) return null
        val lat = latI * 1e-7
        val lon = lonI * 1e-7
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Position(lat, lon, alt, time)
    }

    private fun parsePacket(b: ByteArray): Message.Heard? {
        var from = 0L; var data: ByteArray? = null; var snr = 0f; var rssi = 0; var mqtt = false
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 5 -> from = r.fixed32().toLong() and 0xffffffffL
                f == 4 && w == 2 -> data = r.bytes()
                f == 8 && w == 5 -> snr = Float.fromBits(r.fixed32())
                f == 12 && w == 0 -> rssi = r.varint().toInt()
                f == 14 && w == 0 -> mqtt = r.varint() != 0L
                else -> r.skip(w)
            }
        }
        val d = data ?: return null // encrypted (not for us): nothing to read
        var port = 0; var payload = ByteArray(0)
        val dr = Reader(d)
        while (dr.hasMore()) {
            val (f, w) = dr.tag()
            when {
                f == 1 && w == 0 -> port = dr.varint().toInt()
                f == 2 && w == 2 -> payload = dr.bytes()
                else -> dr.skip(w)
            }
        }
        return when (port) {
            PORT_POSITION -> Message.Heard(from, parsePosition(payload), null, snr, rssi, mqtt)
            PORT_NODEINFO -> Message.Heard(from, null, parseUser(payload), snr, rssi, mqtt)
            else -> Message.Heard(from, null, null, snr, rssi, mqtt) // any packet: the node was heard
        }
    }

    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); break }
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        return out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < b.size
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                if (pos >= b.size) throw IllegalArgumentException("truncated varint")
                val x = b[pos++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
        }
        fun fixed32(): Int {
            if (pos + 4 > b.size) throw IllegalArgumentException("truncated fixed32")
            val v = (b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8) or
                ((b[pos + 2].toInt() and 0xff) shl 16) or ((b[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }
        fun bytes(): ByteArray {
            val n = varint().toInt()
            if (n < 0 || pos + n > b.size) throw IllegalArgumentException("truncated bytes")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun string(): String = String(bytes(), Charsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = varint().toInt(); pos += n }
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            if (pos > b.size) throw IllegalArgumentException("truncated field")
        }
    }
}
