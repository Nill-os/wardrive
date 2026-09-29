package com.dreknil.wardrivebridge

import java.io.File

/** Converts a run into KML for Google Earth: one folder per kind (WiFi, Bluetooth, cell,
 *  detections), placemarks coloured by security so open networks stand out, and the drive
 *  itself as a line in the order things were seen. */
object KmlExporter {
    fun convert(points: List<HistoricalPoint>, title: String, kmlFile: File): File? {
        val located = points.filter { it.lat != 0.0 || it.lon != 0.0 }
        if (located.isEmpty()) return null

        kmlFile.bufferedWriter().use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            out.write("<name>${esc(title)}</name>\n")
            // KML colours are aabbggrr.
            style(out, "open", "ff3c9ef5")     // orange - no encryption
            style(out, "secured", "ffffd000")  // cyan
            style(out, "ble", "ffff009d")      // purple
            style(out, "cell", "ff5ec522")     // green
            style(out, "alert", "ff4444ef")    // red - trackers, Flipper, Flock, skimmers
            out.write("<Style id=\"track\"><LineStyle><color>ffffd000</color><width>3</width></LineStyle></Style>\n")

            val groups = located.groupBy { kindOf(it) }
            for ((kind, pts) in groups.toSortedMap()) {
                out.write("<Folder><name>${esc(kind)} (${pts.size})</name>\n")
                for (p in pts) {
                    val styleId = when {
                        isAlert(p) -> "alert"
                        p.type.equals("BLE", true) -> "ble"
                        p.type.contains("CELL", true) || p.type in setOf("GSM", "LTE", "WCDMA", "NR", "CDMA") -> "cell"
                        isOpen(p.authType) -> "open"
                        else -> "secured"
                    }
                    out.write("<Placemark><name>${esc(p.label.ifBlank { p.mac })}</name><styleUrl>#$styleId</styleUrl>")
                    out.write("<description>${esc("${p.type} · ${p.mac} · ${p.rssi} dBm · ${p.authType} · ${p.firstSeen}")}</description>")
                    out.write("<Point><coordinates>${p.lon},${p.lat},${p.altitude}</coordinates></Point></Placemark>\n")
                }
                out.write("</Folder>\n")
            }

            out.write("<Placemark><name>Route</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
            located.sortedBy { it.firstSeen }.forEach { out.write("${it.lon},${it.lat},0 ") }
            out.write("\n</coordinates></LineString></Placemark>\n")
            out.write("</Document></kml>\n")
        }
        return kmlFile
    }

    private fun kindOf(p: HistoricalPoint): String = when {
        isAlert(p) -> "Detections"
        p.type.equals("BLE", true) -> "Bluetooth"
        p.type.equals("WIFI", true) -> "WiFi"
        else -> "Cell"
    }

    private fun isAlert(p: HistoricalPoint) = p.isTracker || p.isFlipperZero || p.isFlockCamera

    private fun isOpen(auth: String): Boolean {
        val a = auth.uppercase()
        return !a.contains("WPA") && !a.contains("WEP") && !a.contains("OWE") && !a.contains("RSN")
    }

    private fun style(out: java.io.Writer, id: String, color: String) {
        out.write("<Style id=\"$id\"><IconStyle><color>$color</color><scale>0.8</scale>")
        out.write("<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>\n")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
