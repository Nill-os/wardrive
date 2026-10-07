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
        // Skip fix-less rows (logged at 0,0 while there was no GPS fix) - they're
        // kept in the DB so no data is lost, but never uploaded or exported, so
        // nothing lands at "Null Island".
        val rows = dao.observationsForRun(runId).filter { it.lat != 0.0 || it.lon != 0.0 }

        val dir = File(context.getExternalFilesDir(null), "wardrive")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${run.label}.csv")
        file.bufferedWriter().use { w ->
            w.appendLine("WigleWifi-1.6,appRelease=1.0,model=WardriveBridge,release=1.0,device=Android,display=none,board=phone,brand=DIY")
            w.appendLine("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type")
            for (o in rows) {
                if (o.type in CELL_TYPES) {
                    val cell = wigleCell(o) ?: continue // incomplete tower identity: WiGLE drops these too
                    val fcn = if (o.channel > 0) o.channel.toString() else ""
                    w.appendLine(
                        "%s,%s,%s,%s,%s,%s,%d,%.6f,%.6f,%.1f,%.1f,%s".format(
                            Locale.US,
                            cell.key, sanitize(cell.operatorName), cell.authMode, o.firstSeenIso,
                            fcn, fcn, o.rssi,
                            o.lat, o.lon, o.altitudeM, o.accuracyM, o.type,
                        )
                    )
                    continue
                }
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

    private val CELL_TYPES = setOf("GSM", "WCDMA", "LTE", "NR", "CDMA")

    private class WigleCell(val key: String, val operatorName: String, val authMode: String)

    /** A cell row the way the WiGLE app writes it (wiglenet/wigle-wifi-wardriving GsmOperator /
     *  CellReceiver; api.wigle.net/csvFormat-1_6.html), from this app's internal identity
     *  "<TYPE>-<mcc>-<mnc>-<lac|tac>-<cid|ci|nci>" (CDMA: "CDMA-<sid>-<nid>-<bid>"):
     *  MAC = MCC+MNC_LAC_CID, SSID = carrier name, AuthMode = "<TYPE>;<MCC+MNC>", Channel and
     *  Frequency = the EARFCN/ARFCN/UARFCN/NRARFCN. Returns null for a cell WiGLE would drop
     *  (missing or out-of-range MCC/MNC/LAC/CID). */
    private fun wigleCell(o: ObservationEntity): WigleCell? {
        val p = o.mac.split('-')
        if (o.type == "CDMA") {
            if (p.size != 4) return null
            val ids = p.drop(1).map { it.toLongOrNull() ?: return null }
            if (ids.any { it < 0 || it >= Int.MAX_VALUE }) return null
            return WigleCell(ids.joinToString("_"), "", "CDMA;")
        }
        if (p.size != 5) return null
        val mcc = p[1]
        var mnc = p[2]
        val mccN = mcc.toIntOrNull() ?: return null
        val mncN = mnc.toIntOrNull() ?: return null
        if (mccN !in 1..999 || mncN !in 0..999) return null
        mnc = CellIds.canonicalMnc(mcc, mnc) // older rows stored it as a plain number ("310-4")
        val area = p[3].toLongOrNull() ?: return null
        val cid = p[4].toLongOrNull() ?: return null
        if (area <= 0 || area >= Int.MAX_VALUE) return null
        if (cid <= 0 || (o.type != "NR" && cid >= Int.MAX_VALUE)) return null
        val operator = mcc + mnc
        return WigleCell("${operator}_${area}_$cid", CarrierLookup.carrierFor(o.mac) ?: "", "${o.type};$operator")
    }
}
