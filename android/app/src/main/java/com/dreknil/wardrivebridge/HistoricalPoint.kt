package com.dreknil.wardrivebridge

import android.graphics.Color

/** One row read back from a past log's CSV - shared by the Map tab
 * (markers) and the Logs tab's grouped browse view (rows), so both use the
 * same detail formatting and type coloring. */
data class HistoricalPoint(
    val mac: String,
    val label: String,
    val authType: String,
    val firstSeen: String,
    val channel: Int,
    val frequency: Int,
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val accuracy: Double,
    val type: String,
    val isTracker: Boolean = false,
    val isFlipperZero: Boolean = false,
    val isFlockCamera: Boolean = false,
    val isSkimmer: Boolean = false,
    val isDrone: Boolean = false,
    val isMeshRadio: Boolean = false,
    val isAdultToy: Boolean = false,
    val isGlasses: Boolean = false,
    val isActionCam: Boolean = false,
    val isPoliceCam: Boolean = false,
    val isPineapple: Boolean = false,
)

fun HistoricalPoint.formatDetails(): String = buildString {
    if (type.equals("GNSS", ignoreCase = true)) {
        // Satellites don't have a MAC/location/first-seen the way a real
        // radio observation does - synthetic HistoricalPoint fields reused
        // for this (see MainActivity's GnssSatelliteEntity.toHistoricalPoint())
        // get relabeled here instead of showing a meaningless "MAC:"/"RSSI:".
        appendLine("Satellite: $label")
        appendLine("Constellation: $authType")
        appendLine("Last C/N0: $rssi dB-Hz")
        appendLine("Last seen: $firstSeen")
        return@buildString
    }
    appendLine("MAC: $mac")
    appendLine("Label: ${label.ifBlank { "(hidden)" }}")
    appendLine("Type: $type")
    if (authType.isNotBlank()) appendLine("Auth: $authType")
    if (channel > 0) appendLine("Channel: $channel")
    if (frequency > 0) appendLine("Frequency: $frequency MHz")
    appendLine("RSSI: $rssi dBm")
    appendLine("Location: %.6f, %.6f".format(lat, lon))
    if (accuracy > 0) appendLine("GPS accuracy: ±${accuracy.toInt()} m")
    appendLine("First seen: $firstSeen")
}.trim()

fun historicalTypeColor(type: String): Int = when {
    type.equals("BLE", ignoreCase = true) -> Color.parseColor("#22C55E")
    type.equals("WIFI", ignoreCase = true) -> Color.parseColor("#F59E0B")
    type.equals("GNSS", ignoreCase = true) -> Color.parseColor("#38BDF8")
    else -> Color.parseColor("#EC4899") // cell types: GSM/LTE/WCDMA/CDMA
}
