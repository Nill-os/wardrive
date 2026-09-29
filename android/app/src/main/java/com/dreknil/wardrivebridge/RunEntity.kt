package com.dreknil.wardrivebridge

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One scanning session ("run") - replaces one WigleWifi CSV file from
 * before this app moved to Room. `label` keeps the same shape the old
 * filename had (e.g. "phone_20260921_193539") purely for display and for
 * naming a materialized export file, not as a real file path any more. */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val label: String,
    val note: String = "",
    // Null = never successfully sent to wdgwars/WiGLE from this device - the
    // Logs tab's whole "upload if missed" purpose is finding exactly these
    // rows. Set only on a real upload success (see MainActivity.uploadRun()),
    // never just on attempt, so a failed/offline try still shows as missed.
    val uploadedAt: Long? = null,
)

/** A run plus its observation count, for the Logs list - one query instead
 * of one COUNT(*) per row. */
data class RunSummary(
    val id: Long,
    val startedAtMs: Long,
    val label: String,
    val note: String,
    val count: Int,
    val uploadedAt: Long?,
)

/** One row of the Analytics tab's run log - see WardriveDao.runLog(). */
data class RunLogRow(
    val id: Long,
    val startedAtMs: Long,
    val label: String,
    val wifiCount: Int,
    val bleCount: Int,
    val cellCount: Int,
    val firstObsIso: String?,
    val lastObsIso: String?,
)

/** "yyyy-MM-dd" -> count, for the Analytics Calendar heatmap. */
data class DayCount(val day: String, val count: Int)

/** A generic small-integer bucket (hour 0-23, weekday 0-6 Sun-Sat, RSSI/10,
 * accuracy tier) -> count. Reused across Efficiency/Signal sections. */
data class BucketCount(val bucket: Int, val count: Int)

/** A generic string label (auth mode, band, radio type) -> count. */
data class LabelCount(val label: String, val count: Int)

/** One ~250m grid cell with at least one located find - see
 * WardriveDao.territoryGrid(). */
data class TerritoryCell(
    val gridY: Int,
    val gridX: Int,
    val count: Int,
    val lastSeenIso: String?,
    val centerLat: Double,
    val centerLon: Double,
)

/** A device seen again on a genuinely different run - see
 * WardriveDao.regulars(). */
data class RegularDevice(
    val mac: String,
    val label: String,
    val type: String,
    val timesSeen: Int,
    val firstSeenIso: String?,
    val lastSeenIso: String?,
)
