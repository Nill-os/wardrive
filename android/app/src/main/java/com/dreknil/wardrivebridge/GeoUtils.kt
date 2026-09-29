package com.dreknil.wardrivebridge

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Straight-line (great-circle) distance in meters between two lat/lon
 * points - was duplicated identically in ScanService (home-exclusion-zone
 * and persistent-tracker-alert-radius checks) and MainActivity (the
 * tracker-alert dialog's "how far away" distance), risking silent drift if
 * one copy ever changed without the other. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusM * c
}
