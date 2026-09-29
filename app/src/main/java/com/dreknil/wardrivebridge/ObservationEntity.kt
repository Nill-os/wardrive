package com.dreknil.wardrivebridge

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One logged observation - replaces one row of the old per-run WigleWifi
 * CSV file. Same fields, same "only a genuinely new-this-run device gets a
 * row" write policy (see ScanService.onObservation) - just a DB row instead
 * of a CSV line. Indexed on mac (the historical "have I seen this device
 * before" lookup, and the "view all on map" dedup-by-mac query) and on
 * runId (per-run browsing/export/GPX). */
@Entity(
    tableName = "observations",
    indices = [Index("mac"), Index("runId")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val mac: String,
    val label: String,
    val authType: String,
    val firstSeenIso: String,
    val channel: Int,
    val frequencyMHz: Int,
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val accuracyM: Double,
    val type: String, // WIFI / BLE / GSM / LTE / WCDMA / CDMA - same "Type" column WigleWifi CSV uses
    // Persisted copies of Observation's best-effort BLE signature flags (see
    // Observation.kt) - added for the Field Report tab's category counts,
    // 2026-09-27. Only ever set true at capture time; a row logged before
    // this migration existed just reads false, which is honest (never
    // detected, not "detected as safe").
    val isTracker: Boolean = false,
    val isFlipperZero: Boolean = false,
    val isFlockCamera: Boolean = false,
    val isSkimmer: Boolean = false,
    // Same persisted-copy pattern as the four flags above, added 2026-09-28
    // for the Field Report's new categories - see Observation.kt's own
    // comment for what each one means and its source/confidence.
    val isDrone: Boolean = false,
    val isMeshRadio: Boolean = false,
    val isAdultToy: Boolean = false,
    val isGlasses: Boolean = false,
    val isActionCam: Boolean = false,
    val isPoliceCam: Boolean = false,
    val isPineapple: Boolean = false,
)

fun ObservationEntity.toHistoricalPoint() = HistoricalPoint(
    mac = mac, label = label, authType = authType, firstSeen = firstSeenIso,
    channel = channel, frequency = frequencyMHz, rssi = rssi,
    lat = lat, lon = lon, altitude = altitudeM, accuracy = accuracyM, type = type,
    isTracker = isTracker, isFlipperZero = isFlipperZero, isFlockCamera = isFlockCamera, isSkimmer = isSkimmer,
    isDrone = isDrone, isMeshRadio = isMeshRadio, isAdultToy = isAdultToy, isGlasses = isGlasses,
    isActionCam = isActionCam, isPoliceCam = isPoliceCam, isPineapple = isPineapple,
)

fun Observation.toEntity(runId: Long): ObservationEntity {
    val type = when (source) {
        Source.RIG_BLE, Source.PHONE_BLE -> "BLE"
        Source.PHONE_CELL -> authOrType // already GSM/LTE/WCDMA/CDMA from PhoneCellScanner
        Source.RIG_WIFI, Source.PHONE_WIFI -> "WIFI"
    }
    return ObservationEntity(
        runId = runId, mac = mac, label = label, authType = authOrType, firstSeenIso = firstSeenIso,
        channel = channel, frequencyMHz = frequencyMHz, rssi = rssi,
        lat = lat, lon = lon, altitudeM = altitudeM, accuracyM = accuracyM, type = type,
        isTracker = isTracker, isFlipperZero = isFlipperZero, isFlockCamera = isFlockCamera, isSkimmer = isSkimmer,
        isDrone = isDrone, isMeshRadio = isMeshRadio, isAdultToy = isAdultToy, isGlasses = isGlasses,
        isActionCam = isActionCam, isPoliceCam = isPoliceCam, isPineapple = isPineapple,
    )
}
