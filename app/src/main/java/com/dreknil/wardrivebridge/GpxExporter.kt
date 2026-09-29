package com.dreknil.wardrivebridge

import java.io.File

/** Converts a run's observations into a GPX waypoint file, so the same data
 * opens in any GPX-reading tool (Google Earth, Google My Maps, Garmin/
 * Strava, etc.) instead of just WiGLE-format-aware software. Written into
 * the same "wardrive" directory the FileProvider already covers, so sharing
 * it needs no new provider path. */
object GpxExporter {
    fun convert(points: List<HistoricalPoint>, gpxFile: File): File? {
        if (points.isEmpty()) return null

        gpxFile.bufferedWriter().use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.write("<gpx version=\"1.1\" creator=\"WardriveBridge\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            for (p in points) {
                val name = xmlEscape(p.label.ifBlank { p.mac })
                out.write("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\">\n")
                out.write("    <name>$name</name>\n")
                out.write("    <desc>${xmlEscape("${p.type} · ${p.mac} · ${p.rssi}dBm")}</desc>\n")
                if (p.altitude != 0.0) out.write("    <ele>${p.altitude}</ele>\n")
                out.write("  </wpt>\n")
            }
            out.write("</gpx>\n")
        }
        return gpxFile
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
