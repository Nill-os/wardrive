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
                         val rssi: Int, val viaMqtt: Boolean, val hopsAway: Int = -1) : Message()
        object Other : Message()
    }

    /** Meshtastic HardwareModel names (meshtastic/protobufs mesh.proto), for display. */
    fun hardwareName(model: Int): String = HW_MODELS[model] ?: if (model == 0) "unknown" else "model $model"

    private val HW_MODELS: Map<Int, String> = mapOf(
        1 to "TLORA_V2", 2 to "TLORA_V1", 3 to "TLORA_V2_1_1P6", 4 to "TBEAM", 5 to "HELTEC_V2_0", 6 to "TBEAM_V0P7",
        7 to "T_ECHO", 8 to "TLORA_V1_1P3", 9 to "RAK4631", 10 to "HELTEC_V2_1", 11 to "HELTEC_V1", 12 to
        "LILYGO_TBEAM_S3_CORE", 13 to "RAK11200", 14 to "NANO_G1", 15 to "TLORA_V2_1_1P8", 16 to "TLORA_T3_S3", 17 to
        "NANO_G1_EXPLORER", 18 to "NANO_G2_ULTRA", 19 to "LORA_TYPE", 20 to "WIPHONE", 21 to "WIO_WM1110", 22 to
        "RAK2560", 23 to "HELTEC_HRU_3601", 24 to "HELTEC_WIRELESS_BRIDGE", 25 to "STATION_G1", 26 to "RAK11310", 27
        to "SENSELORA_RP2040", 28 to "SENSELORA_S3", 29 to "CANARYONE", 30 to "RP2040_LORA", 31 to "STATION_G2", 32 to
        "LORA_RELAY_V1", 33 to "T_ECHO_PLUS", 34 to "PPR", 35 to "GENIEBLOCKS", 36 to "NRF52_UNKNOWN", 37 to
        "PORTDUINO", 38 to "ANDROID_SIM", 39 to "DIY_V1", 40 to "NRF52840_PCA10059", 41 to "DR_DEV", 42 to "M5STACK",
        43 to "HELTEC_V3", 44 to "HELTEC_WSL_V3", 45 to "BETAFPV_2400_TX", 46 to "BETAFPV_900_NANO_TX", 47 to
        "RPI_PICO", 48 to "HELTEC_WIRELESS_TRACKER", 49 to "HELTEC_WIRELESS_PAPER", 50 to "T_DECK", 51 to
        "T_WATCH_S3", 52 to "PICOMPUTER_S3", 53 to "HELTEC_HT62", 54 to "EBYTE_ESP32_S3", 55 to "ESP32_S3_PICO", 56 to
        "CHATTER_2", 57 to "HELTEC_WIRELESS_PAPER_V1_0", 58 to "HELTEC_WIRELESS_TRACKER_V1_0", 59 to "UNPHONE", 60 to
        "TD_LORAC", 61 to "CDEBYTE_EORA_S3", 62 to "TWC_MESH_V4", 63 to "NRF52_PROMICRO_DIY", 64 to
        "RADIOMASTER_900_BANDIT_NANO", 65 to "HELTEC_CAPSULE_SENSOR_V3", 66 to "HELTEC_VISION_MASTER_T190", 67 to
        "HELTEC_VISION_MASTER_E213", 68 to "HELTEC_VISION_MASTER_E290", 69 to "HELTEC_MESH_NODE_T114", 70 to
        "SENSECAP_INDICATOR", 71 to "TRACKER_T1000_E", 72 to "RAK3172", 73 to "WIO_E5", 74 to
        "RADIOMASTER_900_BANDIT", 75 to "ME25LS01_4Y10TD", 76 to "RP2040_FEATHER_RFM95", 77 to "M5STACK_COREBASIC", 78
        to "M5STACK_CORE2", 79 to "RPI_PICO2", 80 to "M5STACK_CORES3", 81 to "SEEED_XIAO_S3", 82 to "MS24SF1", 83 to
        "TLORA_C6", 84 to "WISMESH_TAP", 85 to "ROUTASTIC", 86 to "MESH_TAB", 87 to "MESHLINK", 88 to
        "XIAO_NRF52_KIT", 89 to "THINKNODE_M1", 90 to "THINKNODE_M2", 91 to "T_ETH_ELITE", 92 to "HELTEC_SENSOR_HUB",
        93 to "MUZI_BASE", 94 to "HELTEC_MESH_POCKET", 95 to "SEEED_SOLAR_NODE", 96 to "NOMADSTAR_METEOR_PRO", 97 to
        "CROWPANEL", 98 to "LINK_32", 99 to "SEEED_WIO_TRACKER_L1", 100 to "SEEED_WIO_TRACKER_L1_EINK", 101 to
        "MUZI_R1_NEO", 102 to "T_DECK_PRO", 103 to "T_LORA_PAGER", 104 to "M5STACK_RESERVED", 105 to "WISMESH_TAG",
        106 to "RAK3312", 107 to "THINKNODE_M5", 108 to "HELTEC_MESH_SOLAR", 109 to "T_ECHO_LITE", 110 to "HELTEC_V4",
        111 to "M5STACK_C6L", 112 to "M5STACK_CARDPUTER_ADV", 113 to "HELTEC_WIRELESS_TRACKER_V2", 114 to
        "T_WATCH_ULTRA", 115 to "THINKNODE_M3", 116 to "WISMESH_TAP_V2", 117 to "RAK3401", 118 to "RAK6421", 119 to
        "THINKNODE_M4", 120 to "THINKNODE_M6", 121 to "MESHSTICK_1262", 122 to "TBEAM_1_WATT", 123 to
        "T5_S3_EPAPER_PRO", 124 to "TBEAM_BPF", 125 to "MINI_EPAPER_S3", 126 to "TDISPLAY_S3_PRO", 127 to
        "HELTEC_MESH_NODE_T096", 128 to "TRACKER_T1000_E_PRO", 129 to "THINKNODE_M7", 130 to "THINKNODE_M8", 131 to
        "THINKNODE_M9", 132 to "HELTEC_V4_R8", 133 to "HELTEC_MESH_NODE_T1", 134 to "STATION_G3", 135 to
        "T_IMPULSE_PLUS", 136 to "T_ECHO_CARD", 137 to "SEEED_WIO_TRACKER_L2", 138 to "CROWPANEL_P4", 139 to
        "HELTEC_MESH_TOWER_V2", 140 to "MESHNOLOGY_W10"
    )

    /** The want_config_id of a ToRadio message, or -1 if it isn't one. */
    fun readWantConfig(toRadio: ByteArray): Long = try {
        val r = Reader(toRadio)
        var nonce = -1L
        while (r.hasMore()) {
            val (f, w) = r.tag()
            if (f == 3 && w == 0) nonce = r.varint() else r.skip(w)
        }
        nonce
    } catch (_: Exception) { -1L }

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
        var hopLimit = 0; var hopStart = 0
        val r = Reader(b)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 5 -> from = r.fixed32().toLong() and 0xffffffffL
                f == 4 && w == 2 -> data = r.bytes()
                f == 8 && w == 5 -> snr = Float.fromBits(r.fixed32())
                f == 12 && w == 0 -> rssi = r.varint().toInt()
                f == 14 && w == 0 -> mqtt = r.varint() != 0L
                f == 9 && w == 0 -> hopLimit = r.varint().toInt()
                f == 15 && w == 0 -> hopStart = r.varint().toInt()
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
        // hop_start is 0 on firmware older than 2.3, so the hop count is unknown there.
        val hops = if (hopStart > 0) (hopStart - hopLimit).coerceAtLeast(0) else -1
        return when (port) {
            PORT_POSITION -> Message.Heard(from, parsePosition(payload), null, snr, rssi, mqtt, hops)
            PORT_NODEINFO -> Message.Heard(from, null, parseUser(payload), snr, rssi, mqtt, hops)
            else -> Message.Heard(from, null, null, snr, rssi, mqtt, hops) // any packet: the node was heard
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
            val n = varint()
            if (n < 0 || pos + n > b.size) throw IllegalArgumentException("truncated bytes")
            return b.copyOfRange(pos, pos + n.toInt()).also { pos += n.toInt() }
        }
        fun string(): String = String(bytes(), Charsets.UTF_8)
        // Lengths come from other people's radios: a negative or oversized one must throw, not
        // move pos backwards (which would loop forever on the main thread).
        fun skip(wire: Int) {
            val n: Long = when (wire) {
                0 -> { varint(); return }
                1 -> 8
                2 -> varint()
                5 -> 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            if (n < 0 || pos + n > b.size) throw IllegalArgumentException("truncated field")
            pos += n.toInt()
        }
    }
}
