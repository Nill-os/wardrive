package com.dreknil.wardrivebridge

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CellReading(val identity: String, val rssi: Int, val rsrp: Int, val rsrq: Int, val fcn: Int = 0)

/**
 * Periodically polls the cellular baseband for towers currently visible to
 * the phone's modem (real, well-documented Android API - not the fabricated
 * "protocol decoding" claims from that pasted WardriveGo description).
 * Requires READ_PHONE_STATE + ACCESS_FINE_LOCATION, same as WiFi scan
 * results, since cell identity is location-sensitive.
 */
class PhoneCellScanner(private val context: Context, private val listener: (Observation) -> Unit) {
    private val telephonyManager = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            emitResults()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
    }

    @SuppressLint("MissingPermission") // caller checks READ_PHONE_STATE + ACCESS_FINE_LOCATION before start()
    private fun emitResults() {
        val cells: List<CellInfo> = try {
            telephonyManager.allCellInfo ?: return
        } catch (e: SecurityException) {
            return
        }

        for (cell in cells) {
            val reading: CellReading = when {
                cell is CellInfoLte -> {
                    val ci = cell.cellIdentity
                    if (ci.ci == CellInfo.UNAVAILABLE) continue // no real cell ID - nothing useful to log
                    val ss = cell.cellSignalStrength
                    CellReading(CellIds.canonical("LTE-${mcc(ci.mccString, ci.mcc)}-${mnc(ci.mncString, ci.mnc)}-${v(ci.tac)}-${ci.ci}"), ss.dbm, vOrZero(ss.rsrp), vOrZero(ss.rsrq), vOrZero(ci.earfcn))
                }
                cell is CellInfoGsm -> {
                    val ci = cell.cellIdentity
                    if (ci.cid == CellInfo.UNAVAILABLE) continue
                    CellReading(CellIds.canonical("GSM-${mcc(ci.mccString, ci.mcc)}-${mnc(ci.mncString, ci.mnc)}-${v(ci.lac)}-${ci.cid}"), cell.cellSignalStrength.dbm, 0, 0, vOrZero(ci.arfcn))
                }
                cell is CellInfoWcdma -> {
                    val ci = cell.cellIdentity
                    if (ci.cid == CellInfo.UNAVAILABLE) continue
                    CellReading(CellIds.canonical("WCDMA-${mcc(ci.mccString, ci.mcc)}-${mnc(ci.mncString, ci.mnc)}-${v(ci.lac)}-${ci.cid}"), cell.cellSignalStrength.dbm, 0, 0, vOrZero(ci.uarfcn))
                }
                cell is CellInfoCdma -> {
                    val ci = cell.cellIdentity
                    if (ci.basestationId == CellInfo.UNAVAILABLE) continue
                    CellReading("CDMA-${v(ci.systemId)}-${v(ci.networkId)}-${ci.basestationId}", cell.cellSignalStrength.dbm, 0, 0)
                }
                Build.VERSION.SDK_INT >= 29 && cell is CellInfoNr -> {
                    val ci = cell.cellIdentity as CellIdentityNr
                    if (ci.nci == CellInfo.UNAVAILABLE_LONG || ci.nci == 0L) continue // neighbour cells often carry no identity at all
                    val ss = cell.cellSignalStrength as CellSignalStrengthNr
                    val mcc = ci.mccString ?: "?"
                    val mnc = ci.mncString ?: "?"
                    val tac = if (ci.tac == CellInfo.UNAVAILABLE) "?" else ci.tac.toString()
                    CellReading(CellIds.canonical("NR-$mcc-$mnc-$tac-${ci.nci}"), ss.dbm, vOrZero(ss.ssRsrp), vOrZero(ss.ssRsrq), vOrZero(ci.nrarfcn))
                }
                else -> continue
            }

            listener(
                Observation(
                    source = Source.PHONE_CELL,
                    mac = reading.identity,
                    label = "",
                    authOrType = cellTypeToken(cell),
                    // The channel number (EARFCN / ARFCN / UARFCN / NRARFCN), which WiGLE's CSV
                    // format carries in both Channel and Frequency for cell rows (0 = not reported).
                    channel = reading.fcn,
                    frequencyMHz = reading.fcn,
                    rssi = reading.rssi,
                    lat = 0.0, lon = 0.0, altitudeM = 0.0, accuracyM = 0.0,
                    firstSeenIso = isoFormat.format(Date()),
                    timestampMs = System.currentTimeMillis(),
                    rsrp = reading.rsrp,
                    rsrq = reading.rsrq,
                )
            )
        }
    }

    // Same UNAVAILABLE-sentinel problem as identity fields, but for signal
    // metrics - 0 stands in for "not reported" rather than leaking
    // Integer.MAX_VALUE into a dBm-shaped field.
    private fun vOrZero(value: Int): Int = if (value == CellInfo.UNAVAILABLE) 0 else value

    // CellIdentity fields report Integer.MAX_VALUE (CellInfo.UNAVAILABLE) when
    // the modem didn't supply that particular value for this reading (common
    // for MCC/MNC/TAC on some carriers/readings) - showing that raw number
    // is meaningless noise, so it becomes "?" instead.
    private fun v(value: Int): String = if (value == CellInfo.UNAVAILABLE) "?" else value.toString()

    // MCC/MNC as the modem reports them, so an MNC keeps its leading zero ("004" is not "4";
    // WiGLE's cell key is MCC+MNC concatenated). The *String getters are API 28+.
    @Suppress("DEPRECATION")
    private fun mcc(str: String?, int: Int): String = if (Build.VERSION.SDK_INT >= 28) str ?: "?" else v(int)
    @Suppress("DEPRECATION")
    private fun mnc(str: String?, int: Int): String = if (Build.VERSION.SDK_INT >= 28) str ?: "?" else v(int)

    private fun cellTypeToken(cell: CellInfo): String = when (cell) {
        is CellInfoLte -> "LTE"
        is CellInfoGsm -> "GSM"
        is CellInfoWcdma -> "WCDMA"
        is CellInfoCdma -> "CDMA"
        is CellInfoNr -> "NR"
        else -> "CELL"
    }

    companion object {
        // Cell towers don't change as fast as WiFi/BLE traffic and polling
        // the modem has its own overhead - 10s keeps this meaningfully
        // separate from the 15s WiFi scan cadence without hammering the radio.
        private const val POLL_INTERVAL_MS = 10000L
    }
}
