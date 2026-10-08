package com.dreknil.wardrivebridge

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/** Snapshot of what GnssStatus.Callback last reported - real, documented
 * per-satellite data (constellation, used-in-fix). */
data class GnssSnapshot(
    val satellitesInView: Int,
    val satellitesUsedInFix: Int,
    val constellations: Set<String>,
)

/** One satellite's reading from a single GnssStatus update - see
 * GnssSatelliteEntity for the persisted, accumulated-across-sessions form
 * of this same data (the Field Report's "GNSS Satellites" section). */
data class GnssSatelliteReading(val constellation: String, val svid: Int, val cn0DbHz: Float, val usedInFix: Boolean)

/** Parsed from a real GSA NMEA sentence via OnNmeaMessageListener - no
 * modern Android location API exposes DOP directly (GnssStatus/Location
 * don't have it), the raw NMEA stream is the only way to get it. 0.0 means
 * "not reported this sentence" (some chipsets omit VDOP/PDOP on certain
 * fixes), not "perfect precision". */
data class DopSnapshot(val pdop: Double, val hdop: Double, val vdop: Double)

/**
 * Plain LocationManager wrapper (no Play Services dependency needed) - just
 * enough to tag observations with the phone's current GPS fix. Falls back to
 * network location if GPS itself has no fix yet, same "something is better
 * than nothing" spirit as the rig's own GPS-gap tolerance.
 */
class LocationTracker(private val context: Context) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var lastLocation: Location? = null
        private set

    var gnssSnapshot: GnssSnapshot? = null
        private set

    var dopSnapshot: DopSnapshot? = null
        private set

    private val nmeaListener = android.location.OnNmeaMessageListener { message, _ ->
        parseGsa(message)?.let { dopSnapshot = it }
    }

    // "$GxGSA,mode1,mode2,sat1..sat12,PDOP,HDOP,VDOP*checksum" - GxGSA
    // (GPGSA/GLGSA/GNGSA/etc. depending on constellation/chipset). Only the
    // last 3 comma-separated fields before the checksum are needed.
    private fun parseGsa(sentence: String): DopSnapshot? {
        val body = sentence.substringBefore('*')
        // Sentence type is always the 3 characters right after the 2-letter
        // talker ID ($GP/$GL/$GN/$GA/$GB/...) - checking that exact position
        // instead of a bare substring search avoids ever matching some
        // unrelated sentence that happens to contain "GSA" elsewhere.
        if (body.length < 6 || body.substring(3, 6) != "GSA") return null
        val fields = body.split(',')
        if (fields.size < 18) return null
        val pdop = fields[15].toDoubleOrNull() ?: return null
        val hdop = fields[16].toDoubleOrNull() ?: return null
        val vdop = fields[17].toDoubleOrNull() ?: return null
        return DopSnapshot(pdop, hdop, vdop)
    }

    /** Fired on every new fix while started - used to build the map's GPS
     * breadcrumb trail. Lands on whatever thread called start() (the main
     * thread, same as onLocationChanged itself), no extra dispatch needed. */
    var onNewFix: ((Location) -> Unit)? = null

    // Fired on every GnssStatus update alongside gnssSnapshot above, with the
    // full per-satellite detail gnssSnapshot's own aggregate counts don't
    // carry - see ScanService for where this gets persisted into
    // GnssSatelliteEntity for the Field Report's "GNSS Satellites" section.
    var onSatelliteStatus: ((List<GnssSatelliteReading>) -> Unit)? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val constellations = LinkedHashSet<String>()
            val readings = ArrayList<GnssSatelliteReading>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                val usedInFix = status.usedInFix(i)
                if (usedInFix) used++
                val constellation = constellationName(status.getConstellationType(i))
                constellations.add(constellation)
                readings.add(GnssSatelliteReading(constellation, status.getSvid(i), status.getCn0DbHz(i), usedInFix))
            }
            gnssSnapshot = GnssSnapshot(status.satelliteCount, used, constellations)
            onSatelliteStatus?.invoke(readings)
        }
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "Unknown"
    }

    // Explicit anonymous object, not a lambda: Kotlin SAM-converts based on
    // the compileSdk stub (where LocationListener's other methods are
    // default), but a real device running an older platform version may not
    // actually have those as default methods at runtime - spelling out all
    // four avoids depending on that at all.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            onNewFix?.invoke(location)
        }

        @Deprecated("Deprecated in platform, kept for pre-30 compatibility")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission") // caller (MainActivity) checks permission first
    fun start() {
        // Every provider the phone has, enabled or not: Android keeps a request on a switched-off
        // provider and starts delivering once Location is switched on - registering only enabled
        // ones left a run started with Location off without GPS for the whole drive.
        val available = locationManager.allProviders
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider in available) try {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, listener)
                locationManager.getLastKnownLocation(provider)?.let {
                    if (lastLocation == null || it.time > (lastLocation?.time ?: 0)) lastLocation = it
                }
            } catch (_: IllegalArgumentException) { // an OEM without this provider after all
            }
        }
        locationManager.registerGnssStatusCallback(gnssCallback, null)
        @Suppress("DEPRECATION") // the Executor-taking overload is API 33+; this one covers minSdk 26 too
        locationManager.addNmeaListener(nmeaListener)
    }

    fun stop() {
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        locationManager.removeNmeaListener(nmeaListener)
        gnssSnapshot = null
        dopSnapshot = null
        // Otherwise a stop/start cycle (pause/resume, or a fresh run right after a prior one)
        // leaves this holding the PRE-STOP fix - start()'s getLastKnownLocation() fallback only
        // overwrites it once the system's cached location is actually newer, which can take a
        // while indoors or right after GPS re-acquires. Every observation tagged in that gap
        // silently gets the old, possibly-far-away coordinates instead of no location at all -
        // a real data-quality bug, not just cosmetic, since nothing downstream can tell a stale
        // fix from a fresh one once it's written to a row.
        lastLocation = null
    }

    /** A position recent enough to log with - the same rule ScanService uses. */
    fun hasFix(): Boolean {
        val loc = lastLocation ?: return false
        return android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < FRESH_FIX_NS
    }

    companion object {
        const val FRESH_FIX_NS = 30_000_000_000L // 30s
    }
}
