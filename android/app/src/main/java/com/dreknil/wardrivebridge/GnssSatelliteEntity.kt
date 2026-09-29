package com.dreknil.wardrivebridge

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One GPS/GNSS satellite this phone's own chip has ever reported, app-only
 * - never uploaded anywhere, not part of the WigleWifi CSV export, purely
 * local/informational (matches the reference app's own "app-only, never
 * uploaded" framing for this screen). Keyed by constellation+svid since
 * satellite IDs are only unique within their own constellation (GPS PRN 1
 * and Galileo SVID 1 are different, unrelated satellites). Persisted (not
 * just kept in memory for the current run) since the count is meant to
 * accumulate across sessions, same spirit as the other Field Report totals. */
@Entity(
    tableName = "gnss_satellites",
    primaryKeys = ["constellation", "svid"],
    indices = [Index("constellation")],
)
data class GnssSatelliteEntity(
    val constellation: String, // GPS/GALILEO/GLONASS/BEIDOU/QZSS/SBAS/IRNSS/UNKNOWN
    val svid: Int,
    val lastCn0DbHz: Float,
    val usedInFix: Boolean,
    val lastSeenIso: String,
)
