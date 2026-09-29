package com.dreknil.wardrivebridge

import android.content.Context
import java.io.File
import java.util.Locale

/** Materializes one run's observations into a real WigleWifi-1.6 CSV file
 * on demand - Export/Upload/GPX-export all need an actual file on disk
 * (FileProvider sharing, multipart upload, GPX conversion), even though
 * storage itself moved to Room. Written into the same app-external
 * "wardrive" directory the old per-run CSVs used to live in, so the
 * existing FileProvider path config needs no changes. Call off the main
 * thread - this does both a DB read and file I/O. */
object CsvExporter {
    fun materialize(context: Context, dao: WardriveDao, runId: Long): File? {
        val run = dao.run(runId) ?: return null
        val rows = dao.observationsForRun(runId)

        val dir = File(context.getExternalFilesDir(null), "wardrive")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${run.label}.csv")
        file.bufferedWriter().use { w ->
            w.appendLine("WigleWifi-1.6,appRelease=1.0,model=WardriveBridge,release=1.0,device=Android,display=none,board=phone,brand=DIY")
            w.appendLine("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type")
            for (o in rows) {
                w.appendLine(
                    "%s,%s,%s,%s,%d,%d,%d,%.6f,%.6f,%.1f,%.1f,%s".format(
                        Locale.US,
                        o.mac, sanitize(o.label), o.authType, o.firstSeenIso,
                        o.channel, o.frequencyMHz, o.rssi,
                        o.lat, o.lon, o.altitudeM, o.accuracyM, o.type,
                    )
                )
            }
        }
        return file
    }

    private fun sanitize(s: String): String = s.replace(",", " ").replace("\n", " ").replace("\r", " ")
}
