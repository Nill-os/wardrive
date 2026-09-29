package com.dreknil.wardrivebridge

import android.graphics.Color

/** Where a single observation came from - shown as a tag in the live feed and
 * used to route it into the right section of the exported CSV. */
enum class Source(val label: String) {
    RIG_WIFI("RIG WiFi"),
    RIG_BLE("RIG BLE"),
    PHONE_WIFI("Phone WiFi"),
    PHONE_BLE("Phone BLE"),
    PHONE_CELL("Phone Cell"),
}

/** Was duplicated identically in ObservationAdapter (source tag background)
 * and MapManager (marker dot color) - one shared mapping so the two views
 * can't silently drift apart. */
val Source.color: Int
    get() = when (this) {
        Source.RIG_WIFI -> Color.parseColor("#9D00FF")
        Source.RIG_BLE -> Color.parseColor("#00F0FF")
        Source.PHONE_WIFI -> Color.parseColor("#F59E0B")
        Source.PHONE_BLE -> Color.parseColor("#22C55E")
        Source.PHONE_CELL -> Color.parseColor("#EC4899")
    }

/**
 * One unified observation, whichever radio/device it came from. Mirrors the
 * fields WigleWifi-1.6 CSV needs so writing that format is a direct mapping,
 * not a separate model per source.
 */
data class Observation(
    val source: Source,
    val mac: String,
    val label: String, // SSID for WiFi, device name for BLE (may be blank)
    val authOrType: String, // WiFi: OPEN/WEP/WPA/WPA2/WPA3. BLE: "BLE".
    val channel: Int, // 0 if not applicable (BLE)
    val frequencyMHz: Int, // 0 if not applicable (BLE)
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val accuracyM: Double,
    val firstSeenIso: String,
    val timestampMs: Long,
    // Best-effort heuristic flag for Apple Find My / Samsung SmartTag style
    // trackers, set only by PhoneBleScanner (the rig's BLE feed doesn't
    // carry raw manufacturer data yet, so RIG_BLE observations never set
    // this). False positives/negatives are expected - this is pattern
    // matching on publicly reverse-engineered advertisement formats, not an
    // official API.
    val isTracker: Boolean = false,
    // Same best-effort category as isTracker, but for three other device
    // types worth flagging live rather than a "is this following me"
    // question - see DeviceSignatureDetection for the actual signatures
    // and how confident each one is. BLE-only, same as isTracker.
    val isFlipperZero: Boolean = false,
    val isFlockCamera: Boolean = false,
    val isSkimmer: Boolean = false,
    // Same best-effort category pattern, added 2026-09-28 - see
    // DeviceSignatureDetection for each signature's source and confidence.
    // isDrone/isMeshRadio are phone-source only (need serviceUuids, which
    // the rig's BLE relay doesn't carry - same limitation as isTracker's
    // sibling field below). isAdultToy/isGlasses/isActionCam/isPoliceCam
    // are name-based, so both PhoneBleScanner and RigLinkManager can set
    // them. isPineapple is WiFi-only (SSID-based), set by
    // PhoneWifiScanner/RigLinkManager's WiFi parsing, not BLE.
    val isDrone: Boolean = false,
    val isMeshRadio: Boolean = false,
    val isAdultToy: Boolean = false,
    val isGlasses: Boolean = false,
    val isActionCam: Boolean = false,
    val isPoliceCam: Boolean = false,
    val isPineapple: Boolean = false,
    // Phone WiFi only - ScanResult.channelWidth was already available via
    // the platform API and simply wasn't being read. 0 if unknown/not
    // applicable (BLE, cell, or rig WiFi - the ESP32 radio is 2.4GHz-only
    // and only ever reports 20/40MHz channels via its own auth string, no
    // separate width field to add there).
    val channelWidthMHz: Int = 0,
    // Vendor name from an offline OUI table, or null if the MAC is
    // locally-administered/randomized (bit 0x02 of the first octet) - a
    // randomized MAC has no real vendor to look up. Populated centrally in
    // MainActivity for every source so rig and phone data get the same
    // treatment, not just whichever scanner happened to compute it.
    val vendor: String? = null,
    val macRandomized: Boolean = false,
    // Phone cell only - real RSRP/RSRQ from CellSignalStrengthLte/Nr, not
    // just the generic dBm value. 0 for GSM/WCDMA/CDMA (no equivalent
    // metric on those radio types) and for every non-cell source.
    val rsrp: Int = 0,
    val rsrq: Int = 0,
    // True when this MAC also appears in a *previous* run's saved logs
    // (checked once per run at startRun(), not continuously) - powers the
    // "seen before" badge, the persistent-tracker alert, and the
    // new-vs-previously-seen session stat.
    val isReturning: Boolean = false,
    // WiFi only - true when the AP isn't broadcasting its SSID (empty SSID
    // in the beacon/probe response). False (not just "unknown") for every
    // non-WiFi source.
    val hidden: Boolean = false,
    // BLE only - the 2-byte company ID from the advertisement's
    // manufacturer-specific data AD structure, or null if the advertisement
    // carried none. Bluetooth SIG-assigned, e.g. 0x004C is Apple.
    val companyId: Int? = null,
    // BLE only, phone source only - the rig's BLE relay doesn't parse full
    // AD structures, just manufacturer data, so RIG_BLE observations never
    // set this. GATT service UUIDs advertised alongside the device, if any.
    val serviceUuids: List<String> = emptyList(),
    // BLE only - whether the advertisement itself allows a connection (some
    // beacons/trackers deliberately advertise non-connectable). Meaningless
    // for WiFi/cell, left true (the default) there.
    val connectable: Boolean = true,
    // Strongest RSSI seen for this MAC so far *this run* - distinct from
    // `rssi`, which is always the most recent reading. Maintained by
    // ScanService.onObservation() across repeat sightings, not set by the
    // scanners themselves (they only know about the one reading in front of
    // them), so it starts equal to `rssi` on a device's first sighting.
    val bestRssi: Int = rssi,
    // How many times this MAC has been observed so far this run. Same
    // merge-across-sightings caveat as bestRssi - scanners always report 1
    // since each only sees its own single reading.
    val timesSeenThisRun: Int = 1,
)
