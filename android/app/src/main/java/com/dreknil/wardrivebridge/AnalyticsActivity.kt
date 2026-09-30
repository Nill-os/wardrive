package com.dreknil.wardrivebridge

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.dreknil.wardrivebridge.databinding.ActivityAnalyticsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device analytics - every local number here comes from this phone's own
 * Room database (see WardriveDao's Analytics section), and the Platforms
 * section's numbers come live from WiGLE's and wdgwars.pl's own account
 * APIs (see AccountStats) using whatever credentials are already saved in
 * Settings - nothing fabricated either way. Two things from the "Wardrive
 * Go" reference this was modeled after are still deliberately left out:
 * "Hardware" (phone radio vs external USB monitor-mode adapter) has no
 * real answer here since this app has no external-adapter support at all,
 * and "Captures" (PMKID/handshake) needs the same monitor-mode hardware -
 * shown as an honest "not available" note instead of a fake 0.
 */
class AnalyticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAnalyticsBinding
    private val dao: WardriveDao by lazy { AppDatabase.get(applicationContext).dao() }
    private val settings: AppSettings by lazy { AppSettings(this) }
    private lateinit var runLogAdapter: RunLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.apply(binding.root, this)

        binding.analyticsBackButton.setOnClickListener { finish() }
        runLogAdapter = RunLogAdapter()
        binding.runLogList.layoutManager = LinearLayoutManager(this)
        binding.runLogList.adapter = runLogAdapter

        loadLocalData()
        loadPlatforms()
    }

    private fun loadLocalData() {
        Thread {
            val runCount = dao.totalRunCount()
            val wifi = dao.lifetimeWifiCount()
            val ble = dao.lifetimeBleCount()
            val cell = dao.lifetimeCellCount()
            val trackers = dao.lifetimeTrackerCount()
            val flipper = dao.lifetimeFlipperCount()
            val flockOrSkimmer = dao.lifetimeFlockCount() + dao.lifetimeSkimmerCount()
            val runLog = dao.runLog()
            val dayCounts = dao.newWifiPerDay()
            val hourCounts = dao.newWifiByHour()
            val weekdayCounts = dao.newWifiByWeekday()
            val securityCounts = dao.securityBreakdown()
            val bandCounts = dao.bandBreakdown()
            val territory = dao.territoryGrid()
            val rssiWifi = dao.rssiHistogram("WIFI")
            val rssiBle = dao.rssiHistogram("BLE")
            val accuracyBuckets = dao.fixAccuracyHistogram()
            val regulars = dao.regulars()

            val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            var longestRun: RunLogRow? = null
            var longestDurationMs = -1L
            var mostWifiRun: RunLogRow? = null
            var mostBleRun: RunLogRow? = null
            for (row in runLog) {
                val durationMs = row.durationMs(isoFormat)
                if (durationMs > longestDurationMs) {
                    longestDurationMs = durationMs
                    longestRun = row
                }
                if (mostWifiRun.let { it == null || row.wifiCount > it.wifiCount }) mostWifiRun = row
                if (mostBleRun.let { it == null || row.bleCount > it.bleCount }) mostBleRun = row
            }

            runOnUiThread {
                binding.analyticsRunCount.text = "$runCount RUNS"
                binding.analyticsLifetimeWifi.text = fmt(wifi)
                binding.analyticsLifetimeBle.text = fmt(ble)
                binding.analyticsLifetimeCell.text = fmt(cell)
                binding.analyticsTrackers.text = "$trackers"
                binding.analyticsFlipper.text = "$flipper"
                binding.analyticsFlockSkimmer.text = "$flockOrSkimmer"

                val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
                val longest = longestRun
                if (longest != null && longestDurationMs > 0) {
                    binding.recordLongestRunValue.text = formatDurationMs(longestDurationMs)
                    binding.recordLongestRunDetail.text = "${longest.label} · ${dateFmt.format(Date(longest.startedAtMs))}"
                } else {
                    binding.recordLongestRunValue.text = "-"
                    binding.recordLongestRunDetail.text = "no runs with observations yet"
                }
                mostWifiRun?.let {
                    binding.recordMostWifiValue.text = "${it.wifiCount}"
                    binding.recordMostWifiDetail.text = dateFmt.format(Date(it.startedAtMs))
                }
                mostBleRun?.let {
                    binding.recordMostBleValue.text = "${it.bleCount}"
                    binding.recordMostBleDetail.text = dateFmt.format(Date(it.startedAtMs))
                }

                binding.runLogEmptyText.visibility = if (runLog.isEmpty()) View.VISIBLE else View.GONE
                binding.runLogList.visibility = if (runLog.isEmpty()) View.GONE else View.VISIBLE
                runLogAdapter.submitList(runLog)

                buildCalendar(dayCounts)
                buildHourChart(hourCounts)
                buildWeekdayChart(weekdayCounts)
                buildLabelBars(binding.securityBarChart, securityCounts, R.color.cyan_500)
                buildLabelBars(binding.bandBarChart, bandCounts, R.color.purple_500)
                buildTerritory(territory)
                buildRssiChart(binding.rssiWifiChart, rssiWifi, R.color.cyan_500)
                buildRssiChart(binding.rssiBleChart, rssiBle, R.color.purple_500)
                buildAccuracyChart(accuracyBuckets)
                buildRegulars(regulars)
            }
        }.start()
    }

    // ---- Calendar ----
    // One square per day that had at least one new WiFi device, ordered
    // chronologically - a simple linear strip rather than the reference
    // app's full 53-week grid, since this app has weeks of history, not
    // years; the shape scales the same way either way.
    private fun buildCalendar(days: List<DayCount>) {
        binding.calendarGrid.removeAllViews()
        if (days.isEmpty()) return
        val max = days.maxOf { it.count }.coerceAtLeast(1)
        for (d in days) {
            val quartile = (d.count.toFloat() / max * 4).toInt().coerceIn(0, 3)
            val color = when (quartile) {
                0 -> Color.parseColor("#0F2A30")
                1 -> Color.parseColor("#0E5A66")
                2 -> Color.parseColor("#00A8BF")
                else -> Color.parseColor("#00F0FF")
            }
            val cell = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setBackgroundColor(color)
                contentDescription = "${d.count} on ${d.day}"
            }
            binding.calendarGrid.addView(cell)
        }
    }

    // ---- Efficiency ----
    private fun buildHourChart(hours: List<BucketCount>) {
        val byHour = hours.associate { it.bucket to it.count }
        val max = (hours.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        binding.hourBarChart.removeAllViews()
        for (h in 0..23) {
            addVerticalBar(binding.hourBarChart, byHour[h] ?: 0, max, R.color.cyan_500, if (h % 3 == 0) "$h" else "")
        }
    }

    private fun buildWeekdayChart(weekdays: List<BucketCount>) {
        val labels = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        val byDay = weekdays.associate { it.bucket to it.count }
        val max = (weekdays.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        binding.weekdayBarChart.removeAllViews()
        for (i in 0..6) {
            addVerticalBar(binding.weekdayBarChart, byDay[i] ?: 0, max, R.color.purple_500, labels[i].take(1))
        }
    }

    // ---- Trends ----
    private fun buildLabelBars(container: LinearLayout, items: List<LabelCount>, colorRes: Int) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(dimText("no data yet"))
            return
        }
        val max = items.maxOf { it.count }.coerceAtLeast(1)
        for (item in items) {
            container.addView(horizontalBarRow(item.label, item.count, max, colorRes))
        }
    }

    // ---- Territory ----
    private fun buildTerritory(cells: List<TerritoryCell>) {
        binding.territoryCells.text = fmt(cells.size)
        // Each cell is ~0.0025 deg lat tall (~278m) - real conversion, not a
        // round guess: 1 deg latitude is ~69 miles, so one cell edge is
        // 69 * 0.0025 miles.
        val cellEdgeMi = 69.0 * 0.0025
        val areaMi2 = cells.size * cellEdgeMi * cellEdgeMi
        binding.territoryArea.text = "%.1f mi2".format(areaMi2)

        binding.densestCellsList.removeAllViews()
        if (cells.isEmpty()) {
            binding.densestCellsList.addView(dimText("no located finds yet"))
            return
        }
        val max = cells.first().count
        for (cell in cells.take(5)) {
            val label = "%.3f, %.3f".format(cell.centerLat, cell.centerLon)
            binding.densestCellsList.addView(horizontalBarRow(label, cell.count, max, R.color.cyan_500))
        }
    }

    // ---- Signal ----
    private fun buildRssiChart(container: LinearLayout, buckets: List<BucketCount>, colorRes: Int) {
        container.removeAllViews()
        if (buckets.isEmpty()) {
            container.addView(dimText("no data yet"))
            return
        }
        val max = buckets.maxOf { it.count }.coerceAtLeast(1)
        for (b in buckets) {
            addVerticalBar(container, b.count, max, colorRes, if (b.bucket % 20 == 0) "${b.bucket}" else "")
        }
    }

    private fun buildAccuracyChart(buckets: List<BucketCount>) {
        val labels = arrayOf("<10m", "10-25m", "25-50m", "50-100m", ">100m")
        val byBucket = buckets.associate { it.bucket to it.count }
        val max = (buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        binding.accuracyChart.removeAllViews()
        for (i in 0..4) {
            addVerticalBar(binding.accuracyChart, byBucket[i] ?: 0, max, R.color.cyan_500, labels[i])
        }
    }

    // ---- Regulars ----
    private fun buildRegulars(regulars: List<RegularDevice>) {
        binding.regularsList.removeAllViews()
        if (regulars.isEmpty()) {
            binding.regularsList.addView(dimText("nothing seen across more than one run yet"))
            return
        }
        for (r in regulars) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, dp(4), 0, dp(4))
            }
            val name = TextView(this).apply {
                text = r.label.ifBlank { r.mac }
                setTextColor(color(R.color.text_primary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }
            val times = TextView(this).apply {
                text = "${r.timesSeen}x"
                setTextColor(color(R.color.cyan_500))
                textSize = 12f
            }
            row.addView(name)
            row.addView(times)
            binding.regularsList.addView(row)
        }
    }

    // ---- Platforms (live API data) + Territory Captures (wdgwars gang game) ----
    private fun loadPlatforms() {
        Thread {
            val wigle = AccountStats.fetchWigle(settings.wigleToken)
            val wdgwars = AccountStats.fetchWdgwars(settings.wdgwarsKey)
            runOnUiThread {
                binding.platformsLoading.visibility = View.GONE

                if (wigle != null) {
                    binding.wigleSection.visibility = View.VISIBLE
                    binding.wigleDetail.text = buildString {
                        appendLine("Discovered WiFi: ${fmt(wigle.discoveredWifi)}   ·   BT: ${fmt(wigle.discoveredBt)}   ·   Cell: ${fmt(wigle.discoveredCell)}")
                        appendLine("Rank: ${fmt(wigle.rank)}   ·   This month: #${fmt(wigle.monthRank)} (${fmt(wigle.eventMonthCount)} new)")
                        append("Total WiFi locations on file: ${fmt(wigle.totalWiFiLocations)}")
                    }
                }

                if (wdgwars != null) {
                    binding.territoryCapturesEmpty.visibility = if (wdgwars.recentCaptures.isEmpty()) View.VISIBLE else View.GONE
                    binding.territoryCapturesList.removeAllViews()
                    for (cap in wdgwars.recentCaptures.take(10)) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            setPadding(0, dp(3), 0, dp(3))
                        }
                        row.addView(TextView(this).apply {
                            text = "${cap.apCount} APs" + (cap.defenderGang?.let { " · from $it" } ?: " · unclaimed")
                            setTextColor(color(R.color.text_primary))
                            textSize = 11f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this).apply {
                            text = cap.whenIso.take(16)
                            setTextColor(color(R.color.text_secondary))
                            textSize = 10f
                        })
                        binding.territoryCapturesList.addView(row)
                    }
                    binding.wdgwarsSection.visibility = View.VISIBLE
                    binding.wdgwarsDetail.text = buildString {
                        appendLine("Score: ${fmt(wdgwars.total)} pts   ·   WiFi ${fmt(wdgwars.wifi)}   ·   BLE ${fmt(wdgwars.ble)}")
                        appendLine("Last 7 days: ${fmt(wdgwars.recent7d)} new   ·   Credits: ${fmt(wdgwars.creditsBalance)}   ·   Badges: ${wdgwars.badgeCount}")
                        if (wdgwars.gang != null) appendLine("Gang: ${wdgwars.gang} (${wdgwars.gangRole ?: "member"})")
                        append("Upload budget: ${fmt(wdgwars.newApLimitUsed)} / ${fmt(wdgwars.newApLimitCap)} (24h rolling)")
                    }
                    binding.wdgwarsDevices.text = if (wdgwars.devices.isEmpty()) {
                        "No per-device upload history yet."
                    } else {
                        buildString {
                            appendLine("PER UPLOADING DEVICE:")
                            for (d in wdgwars.devices) {
                                append("  ${d.name}: ${fmt(d.networks)} networks, ${d.uploads} uploads, last ${d.lastUpload.take(16)}\n")
                            }
                        }.trim()
                    }
                }

                if (wigle == null && wdgwars == null) {
                    binding.platformsLoading.visibility = View.VISIBLE
                    binding.platformsLoading.text = "No WiGLE token or wdgwars key set in Settings, or both requests failed."
                }
            }
        }.start()
    }

    // ---- small view-building helpers ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = androidx.core.content.ContextCompat.getColor(this, res)

    private fun dimText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_secondary))
        textSize = 11f
    }

    private fun addVerticalBar(container: LinearLayout, value: Int, max: Int, colorRes: Int, label: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val barHeightDp = (value.toFloat() / max * 70).toInt().coerceAtLeast(if (value > 0) 2 else 0)
        val bar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(barHeightDp)).also { it.gravity = Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(color(colorRes))
        }
        column.addView(bar)
        if (label.isNotEmpty()) {
            column.addView(TextView(this).apply {
                text = label
                textSize = 8f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(color(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        container.addView(column)
    }

    private fun horizontalBarRow(label: String, value: Int, max: Int, colorRes: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(3), 0, dp(3))
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        labelRow.addView(TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_primary))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        labelRow.addView(TextView(this).apply {
            text = fmt(value)
            setTextColor(color(colorRes))
            textSize = 11f
        })
        row.addView(labelRow)
        val barBg = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).also { it.topMargin = dp(2) }
            setBackgroundColor(Color.parseColor("#151522"))
        }
        val barWidthPercent = (value.toFloat() / max * 100).toInt().coerceIn(1, 100)
        barBg.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, barWidthPercent.toFloat())
            setBackgroundColor(color(colorRes))
        })
        barBg.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - barWidthPercent).toFloat())
        })
        row.addView(barBg)
        return row
    }

    private fun fmt(n: Int): String = fmtCount(n)
}
