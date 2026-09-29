package com.dreknil.wardrivebridge

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WardriveDao {
    @Insert
    fun insertRun(run: RunEntity): Long

    @Insert
    fun insertObservation(observation: ObservationEntity)

    @Query("SELECT * FROM runs WHERE id = :runId")
    fun run(runId: Long): RunEntity?

    @Query(
        """
        SELECT r.id AS id, r.startedAtMs AS startedAtMs, r.label AS label, r.note AS note, r.uploadedAt AS uploadedAt, COUNT(o.id) AS count
        FROM runs r LEFT JOIN observations o ON o.runId = r.id
        GROUP BY r.id ORDER BY r.startedAtMs DESC
        """
    )
    fun allRunSummaries(): List<RunSummary>

    @Query("SELECT id FROM runs ORDER BY startedAtMs DESC LIMIT 1")
    fun mostRecentRunId(): Long?

    @Query("UPDATE runs SET note = :note WHERE id = :runId")
    fun setNote(runId: Long, note: String)

    // Only ever set on a real upload success (see MainActivity.uploadRun()) -
    // never touched on a failed/skipped attempt, so a run that failed to
    // reach wdgwars/WiGLE still shows up as missed and can be retried.
    @Query("UPDATE runs SET uploadedAt = :atMs WHERE id = :runId")
    fun markUploaded(runId: Long, atMs: Long)

    @Query("SELECT * FROM observations WHERE runId = :runId")
    fun observationsForRun(runId: Long): List<ObservationEntity>

    // "Have I seen this device before, and where" (historicalIndex,
    // persistent-tracker alerting) and "view everything on the map" both
    // want one row per unique MAC, most recent sighting wins - same dedup
    // semantics CsvHistoryLoader.loadAll() used before this migration.
    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations GROUP BY mac)")
    fun latestPerMac(): List<ObservationEntity>

    // REPLACE on the (constellation, svid) primary key is exactly the
    // "insert or update this satellite's latest reading" upsert the Field
    // Report's accumulating GNSS Satellites section wants - see
    // GnssSatelliteEntity's own comment.
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun upsertGnssSatellites(satellites: List<GnssSatelliteEntity>)

    @Query("SELECT * FROM gnss_satellites ORDER BY constellation, svid")
    fun allGnssSatellites(): List<GnssSatelliteEntity>

    @Query("DELETE FROM runs WHERE id = :runId")
    fun deleteRunRow(runId: Long)

    @Query("DELETE FROM observations WHERE runId = :runId")
    fun deleteObservationsForRun(runId: Long)

    @Transaction
    fun deleteRunWithObservations(runId: Long) {
        deleteObservationsForRun(runId)
        deleteRunRow(runId)
    }

    @Query("SELECT id FROM runs WHERE startedAtMs < :cutoffMs")
    fun runIdsOlderThan(cutoffMs: Long): List<Long>

    @Query("DELETE FROM runs WHERE startedAtMs < :cutoffMs")
    fun deleteRunRowsOlderThan(cutoffMs: Long)

    @Transaction
    fun deleteRunsOlderThan(cutoffMs: Long) {
        for (id in runIdsOlderThan(cutoffMs)) deleteObservationsForRun(id)
        deleteRunRowsOlderThan(cutoffMs)
    }

    // ---- Analytics / Field Report (2026-09-27) ----
    // All of these are real, on-device aggregates - no fabricated categories
    // or synced/server-side numbers. Counts are DISTINCT mac so a device
    // seen 500 times across many runs counts once, matching "how many
    // different devices have I ever found" rather than "how many rows".

    @Query("SELECT COUNT(*) FROM runs")
    fun totalRunCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE type = 'WIFI'")
    fun lifetimeWifiCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE type = 'BLE'")
    fun lifetimeBleCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE type NOT IN ('WIFI', 'BLE')")
    fun lifetimeCellCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE isTracker = 1")
    fun lifetimeTrackerCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE isFlipperZero = 1")
    fun lifetimeFlipperCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE isFlockCamera = 1")
    fun lifetimeFlockCount(): Int

    @Query("SELECT COUNT(DISTINCT mac) FROM observations WHERE isSkimmer = 1")
    fun lifetimeSkimmerCount(): Int

    // One row per unique device ever seen (most recent sighting wins),
    // filtered to one Field Report category - the actual browsable list
    // behind each category's expand action.
    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE type = :type GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacByType(type: String): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE type NOT IN ('WIFI', 'BLE') GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacCell(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE isTracker = 1 GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacTracker(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE isFlipperZero = 1 GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacFlipper(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE isFlockCamera = 1 GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacFlock(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE id IN (SELECT MAX(id) FROM observations WHERE isSkimmer = 1 GROUP BY mac) ORDER BY firstSeenIso DESC")
    fun latestPerMacSkimmer(): List<ObservationEntity>

    // Per-run breakdown for the Analytics run log - firstSeenIso sorts
    // correctly as text (fixed "yyyy-MM-dd HH:mm:ss" format), so MIN/MAX
    // on it gives real first/last-observation timestamps per run without
    // needing a separate stored duration column.
    @Query(
        """
        SELECT r.id AS id, r.startedAtMs AS startedAtMs, r.label AS label,
               COUNT(CASE WHEN o.type = 'WIFI' THEN 1 END) AS wifiCount,
               COUNT(CASE WHEN o.type = 'BLE' THEN 1 END) AS bleCount,
               COUNT(CASE WHEN o.type NOT IN ('WIFI', 'BLE') THEN 1 END) AS cellCount,
               MIN(o.firstSeenIso) AS firstObsIso,
               MAX(o.firstSeenIso) AS lastObsIso
        FROM runs r LEFT JOIN observations o ON o.runId = r.id
        GROUP BY r.id ORDER BY r.startedAtMs DESC
        """
    )
    fun runLog(): List<RunLogRow>

    // ---- Calendar / Efficiency / Trends / Territory / Signal / Regulars ----
    // firstSeenIso is a fixed "yyyy-MM-dd HH:mm:ss" string, so substr() pulls
    // the date/hour directly and SQLite's strftime('%w', ...) gives a real
    // weekday (0=Sunday) from the date substring - no separate date columns
    // needed for any of this.

    @Query(
        """
        SELECT substr(firstSeenIso, 1, 10) AS day, COUNT(*) AS count
        FROM observations WHERE type = 'WIFI'
        GROUP BY day ORDER BY day
        """
    )
    fun newWifiPerDay(): List<DayCount>

    @Query(
        """
        SELECT CAST(substr(firstSeenIso, 12, 2) AS INTEGER) AS bucket, COUNT(*) AS count
        FROM observations WHERE type = 'WIFI'
        GROUP BY bucket ORDER BY bucket
        """
    )
    fun newWifiByHour(): List<BucketCount>

    @Query(
        """
        SELECT CAST(strftime('%w', substr(firstSeenIso, 1, 10)) AS INTEGER) AS bucket, COUNT(*) AS count
        FROM observations WHERE type = 'WIFI'
        GROUP BY bucket ORDER BY bucket
        """
    )
    fun newWifiByWeekday(): List<BucketCount>

    // authType is already OPEN/WEP/WPA/WPA2/WPA3 for WiFi rows (see
    // PhoneWifiScanner/RigLinkManager's authFromCapabilities) - a real
    // security-mix trend, not inferred.
    @Query(
        """
        SELECT authType AS label, COUNT(DISTINCT mac) AS count
        FROM observations WHERE type = 'WIFI' AND authType != ''
        GROUP BY authType ORDER BY count DESC
        """
    )
    fun securityBreakdown(): List<LabelCount>

    // GROUP BY repeats the CASE expression instead of the "label" alias -
    // observations already has a real `label` column (the SSID), and
    // SQLite resolves a GROUP BY name against an actual table column before
    // a SELECT-list alias when both exist. Grouping by the alias here
    // silently grouped by SSID instead of by band, turning a 3-4-row result
    // into one row per unique network (thousands of them) - found via a
    // real ANR while building this row-by-row in the UI (2026-09-27).
    @Query(
        """
        SELECT
          CASE
            WHEN frequencyMHz BETWEEN 2400 AND 2500 THEN '2.4GHz'
            WHEN frequencyMHz BETWEEN 4900 AND 5900 THEN '5GHz'
            WHEN frequencyMHz > 5900 THEN '6GHz'
            ELSE 'unknown'
          END AS label,
          COUNT(DISTINCT mac) AS count
        FROM observations WHERE type = 'WIFI' AND frequencyMHz > 0
        GROUP BY
          CASE
            WHEN frequencyMHz BETWEEN 2400 AND 2500 THEN '2.4GHz'
            WHEN frequencyMHz BETWEEN 4900 AND 5900 THEN '5GHz'
            WHEN frequencyMHz > 5900 THEN '6GHz'
            ELSE 'unknown'
          END
        ORDER BY count DESC
        """
    )
    fun bandBreakdown(): List<LabelCount>

    // RSSI bucketed to the nearest 10dBm, one histogram per radio type - a
    // real signal-strength distribution, matching how RF spectrum analyzers
    // usually bucket it.
    @Query(
        """
        SELECT (rssi / 10) * 10 AS bucket, COUNT(*) AS count
        FROM observations WHERE type = :type AND rssi != 0
        GROUP BY bucket ORDER BY bucket
        """
    )
    fun rssiHistogram(type: String): List<BucketCount>

    @Query(
        """
        SELECT
          CASE
            WHEN accuracyM < 10 THEN 0
            WHEN accuracyM < 25 THEN 1
            WHEN accuracyM < 50 THEN 2
            WHEN accuracyM < 100 THEN 3
            ELSE 4
          END AS bucket,
          COUNT(*) AS count
        FROM observations WHERE accuracyM > 0
        GROUP BY bucket ORDER BY bucket
        """
    )
    fun fixAccuracyHistogram(): List<BucketCount>

    // ~250m grid cells (0.0025 deg of latitude), one row per cell with at
    // least one located find - real territory coverage, not a heatmap
    // image. lastSeenIso lets the UI flag stale cells the same way the
    // reference app's Territory section does.
    @Query(
        """
        SELECT
          CAST(lat / 0.0025 AS INTEGER) AS gridY,
          CAST(lon / 0.0025 AS INTEGER) AS gridX,
          COUNT(*) AS count,
          MAX(firstSeenIso) AS lastSeenIso,
          AVG(lat) AS centerLat, AVG(lon) AS centerLon
        FROM observations WHERE lat != 0 OR lon != 0
        GROUP BY gridY, gridX ORDER BY count DESC
        """
    )
    fun territoryGrid(): List<TerritoryCell>

    // Devices seen again on a genuinely different run (not just re-advertised
    // within the same run - that's already deduped away before a row is ever
    // written, see ScanService.onObservation) - stationary neighbours and
    // repeat visitors, matching the reference app's "Regulars" section.
    @Query(
        """
        SELECT mac, label, type, COUNT(DISTINCT runId) AS timesSeen,
               MIN(firstSeenIso) AS firstSeenIso, MAX(firstSeenIso) AS lastSeenIso
        FROM observations
        GROUP BY mac HAVING timesSeen > 1
        ORDER BY timesSeen DESC LIMIT 25
        """
    )
    fun regulars(): List<RegularDevice>

    @Query("SELECT type AS label, COUNT(DISTINCT mac) AS count FROM observations GROUP BY type ORDER BY count DESC")
    fun countsByType(): List<LabelCount>
}
