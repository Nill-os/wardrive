package com.dreknil.wardrivebridge

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Exports one run in airodump-ng's CSV layout - the "Aircrack" / Kismet-family
 * CSV a lot of wardriving and RF tooling ingests, alongside the WigleWifi CSV
 * the app already writes. airodump-ng's file has two sections: an access-point
 * table, a blank line, then a client (station) table. This app only records
 * broadcasts it sees, so every WiFi observation becomes an AP row and every
 * BLE observation becomes a station row (its nearest-AP field left blank,
 * which is how airodump marks an unassociated client). Fields airodump fills
 * from association state we never have (# IV, LAN IP, Key) are written empty,
 * exactly as airodump does when it has no value.
 *
 * The column order and the leading spaces after each comma match real
 * airodump-ng output so parsers written against it (Kismet importers,
 * aircrack tooling) accept the file unchanged. Call off the main thread.
 */
object AircrackExporter {
    fun export(context: Context, dao: WardriveDao, runId: Long): File? {
        val run = dao.run(runId) ?: return null
        val rows = dao.observationsForRun(runId)
        if (rows.isEmpty()) return null

        val dir = File(context.getExternalFilesDir(null), "wardrive")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${run.label}-airodump.csv")

        val aps = rows.filter { it.type == "WIFI" }
        val clients = rows.filter { it.type == "BLE" }

        file.bufferedWriter().use { w ->
            // Access-point section.
            w.append("BSSID, First time seen, Last time seen, channel, Speed, Privacy, ")
            w.append("Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key\r\n")
            for (o in aps) {
                val seen = airodumpTime(o.firstSeenIso)
                val privacy = privacyOf(o.authType)
                w.append("${o.mac}, $seen, $seen, ${o.channel}, -1, $privacy, ")
                w.append(", ${authOf(o.authType)}, ${o.rssi}, 0, 0, 0.  0.  0.  0, ${o.label.length}, ${sanitize(o.label)}, \r\n")
            }
            w.append("\r\n")
            // Client (station) section - BLE devices as unassociated clients.
            w.append("Station MAC, First time seen, Last time seen, Power, # packets, BSSID, Probed ESSIDs\r\n")
            for (o in clients) {
                val seen = airodumpTime(o.firstSeenIso)
                w.append("${o.mac}, $seen, $seen, ${o.rssi}, 1, (not associated), ${sanitize(o.label)}\r\n")
            }
        }
        return file
    }

    // airodump timestamps are "yyyy-MM-dd HH:mm:ss"; the app already stores
    // firstSeenIso in exactly that fixed format, so pass it straight through.
    private fun airodumpTime(iso: String): String = iso

    // airodump's Privacy column is the encryption suite tokens; map the app's
    // normalized auth strings to the closest airodump equivalent.
    private fun privacyOf(auth: String): String = when {
        auth.contains("WPA3") -> "WPA3"
        auth.contains("WPA2") -> "WPA2"
        auth.contains("WPA") -> "WPA"
        auth.contains("WEP") -> "WEP"
        auth.contains("OWE") -> "OPN"
        auth.isBlank() || auth == "OPEN" -> "OPN"
        else -> auth
    }

    private fun authOf(auth: String): String = when {
        auth.contains("WPA") -> "PSK"
        else -> ""
    }

    private fun sanitize(s: String): String = s.replace(",", " ").replace("\r", " ").replace("\n", " ")
}
