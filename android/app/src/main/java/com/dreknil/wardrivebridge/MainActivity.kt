package com.dreknil.wardrivebridge

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import com.dreknil.wardrivebridge.databinding.ActivityMainBinding
import com.dreknil.wardrivebridge.databinding.ItemMenuRowBinding
import java.io.File

/**
 * UI layer only - all scanning/session state (dedup groups, CSV writing,
 * privacy filtering, the rig's USB connection) lives in ScanService, which
 * this Activity binds to. The service's lifecycle does not depend on this
 * Activity being alive, so a locked screen or a backgrounded app no longer
 * risks silently losing mid-drive data - see ScanService's own doc comment.
 */
class MainActivity : AppCompatActivity(), ScanService.SessionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ObservationAdapter
    private lateinit var appSettings: AppSettings
    private lateinit var mapManager: MapManager
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var recentRunsAdapter: LogsAdapter
    private lateinit var historicalAdapter: HistoricalAdapter
    private var browsingAllLogs = false
    private val expandedHistGroups = mutableMapOf<String, Boolean>()
    private val uploadManager by lazy { UploadManager(applicationContext) }
    private var huntDialog: HuntDialog? = null
    private val dao: WardriveDao by lazy { AppDatabase.get(applicationContext).dao() }

    // ---- Navigation shell (Dashboard/WiFi/Bluetooth/Terminal bottom nav +
    // a menu->detail overlay for WiFi/Bluetooth tool screens) ----
    private enum class AppSection { DASHBOARD, TOOLS, TERMINAL }
    private enum class DetailKind { NONE, FEED, MAP, LOGS, FLOOR_PLAN }
    private var currentSection = AppSection.DASHBOARD
    private var currentDetailKind = DetailKind.NONE
    private var detailFeedSources: Set<Source> = emptySet()
    private var detailFeedExtra: ((Observation) -> Boolean)? = null
    private var detailSearchQuery = ""
    private val terminalLines = ArrayDeque<String>()

    private var scanService: ScanService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val bound = (service as ScanService.LocalBinder).service
            scanService = bound
            bound.listener = this@MainActivity
            // The service may have kept running (and collecting) while this
            // Activity was gone - resync the UI to whatever it's already
            // doing instead of assuming a fresh start.
            binding.startStopButton.text = if (bound.running) "> STOP" else "> START"
            binding.pauseResumeButton.visibility = if (bound.running) View.VISIBLE else View.GONE
            binding.pauseResumeButton.text = if (bound.paused) "> RESUME" else "> PAUSE"
            for (map in bound.groups.values) for (obs in map.values) mapManager.upsertLive(obs)
            refreshDetailFeedIfShown()
            updateStatusText()
            syncPreviewScanning() // if a live tool was already open before the service bound, start scanning for it now
            maybeStartRunFromShortcut()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            scanService = null
        }
    }

    // Display order: rig first, then phone; WiFi before BLE before Cell
    // within each - matches "group by phone and rig then by type".
    private val groupOrder = listOf(
        Source.RIG_WIFI, Source.RIG_BLE, Source.PHONE_WIFI, Source.PHONE_BLE, Source.PHONE_CELL
    )

    // File-tree-style expand/collapse per group, tapped via the header row.
    // Collapsed by default - counts are visible in each header without
    // needing to expand anything, and collapsing never affects collection,
    // only what's currently shown.
    private val expandedGroups = Source.entries.associateWith { false }.toMutableMap()
    private var logsSearchQuery = ""
    private var selectionMode = false
    private val selectedRunIds = mutableSetOf<Long>()
    private var heatmapEnabled = false
    // Battery level/charging state, cached between updateStatusText() calls
    // - that function runs on every single observation while driving (a
    // dedicated per-run column even before this comment already said so, see
    // its gpsDot.setOnLongClickListener comment above), but a fresh
    // registerReceiver(null, ACTION_BATTERY_CHANGED) is a Binder round-trip
    // to the system, and battery percentage doesn't meaningfully change
    // between one observation and the next - throttling this to a few-
    // second cadence avoids paying that cost on every single sighting.
    private var lastBatteryText = "PWR: --%"
    private var lastBatteryCheckMs = 0L

    private fun toggleGroup(source: Source) {
        expandedGroups[source] = !(expandedGroups[source] ?: false)
        adapter.submitList(buildGroupedFeed(detailFeedSources, detailFeedExtra))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results checked on demand via hasPermission() below */ }

    private var currentFloorPlanImage: File? = null
    private var floorPlanMarkers: MutableList<FloorPlanMarker> = mutableListOf()
    private val floorPlanImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFloorPlanImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.apply(binding.root, this)

        appSettings = AppSettings(this)
        if (intent?.action == ScanService.ACTION_START) startRunRequested = true
        adapter = ObservationAdapter(
            onHeaderClick = { source -> toggleGroup(source) },
            onRowLongClick = { obs -> startHunt(obs) },
            onRowClick = { obs -> showObservationDetail(obs) },
        )
        binding.detailFeedList.layoutManager = LinearLayoutManager(this)
        binding.detailFeedList.adapter = adapter

        binding.linkButton.setOnClickListener {
            requestNeededPermissions()
            scanService?.connectRig()
        }

        binding.startStopButton.setOnClickListener {
            val service = scanService ?: return@setOnClickListener
            if (service.running) service.stopRun(notifyRig = true, reason = "phone STOP button") else service.startRun(notifyRig = true, reason = "phone START button")
        }

        binding.pauseResumeButton.setOnClickListener {
            val service = scanService ?: return@setOnClickListener
            if (service.paused) service.resumeRun() else service.pauseRun()
        }

        // Full GPS/rig detail isn't part of the glance path any more -
        // available on demand via a long-press instead of permanently
        // taking up space next to the numbers that actually matter at a
        // stoplight. Set once here (not inside updateStatusText(), which
        // runs on every single observation while driving) and read live
        // state fresh at click time instead of from a stale capture.
        binding.gpsDot.setOnLongClickListener {
            val service = scanService
            val loc = service?.locationTracker?.lastLocation
            val text = loc?.let {
                val snap = service.locationTracker.gnssSnapshot
                val dop = service.locationTracker.dopSnapshot
                val satText = snap?.let { s -> " · ${s.satellitesUsedInFix}/${s.satellitesInView} sats" +
                    if (s.constellations.isNotEmpty()) " (${s.constellations.joinToString("+")})" else "" } ?: ""
                val dopText = dop?.let { d -> " · HDOP %.1f".format(d.hdop) } ?: ""
                "Fix (%.5f, %.5f)%s%s".format(it.latitude, it.longitude, satText, dopText)
            } ?: "No GPS fix"
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            true
        }
        binding.rigDot.setOnLongClickListener {
            val connected = scanService?.rigConnected == true
            Toast.makeText(this, "Rig: " + if (connected) "connected" else "not connected", Toast.LENGTH_SHORT).show()
            true
        }

        binding.rigHealthText.setOnClickListener { confirmRigUpload() }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.quickActionUpload.setOnClickListener { uploadCsv() }
        binding.quickActionMyUploads.setOnClickListener {
            browsingAllLogs = false
            binding.browseAllButton.text = "> BROWSE ALL"
            showDetail(DetailKind.LOGS, "My Uploads")
        }
        binding.quickActionAnalytics.setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        binding.quickActionFieldReport.setOnClickListener {
            browsingAllLogs = true
            binding.browseAllButton.text = "> SHOW FILES"
            showDetail(DetailKind.LOGS, "Field Report")
        }
        binding.statsButton.setOnClickListener { showSessionStats() }
        binding.allTimeStatsButton.setOnClickListener { showAllTimeStats() }

        mapManager = MapManager(applicationContext, binding.mapView) { title, body, mac ->
            showDetailDialog(title, body, mac)
        }
        binding.recenterMapButton.setOnClickListener {
            val loc = scanService?.locationTracker?.lastLocation
            if (loc != null) mapManager.recenterOn(loc.latitude, loc.longitude) else mapManager.recenterOnLive()
        }
        binding.heatmapToggleButton.setOnClickListener {
            heatmapEnabled = !heatmapEnabled
            mapManager.setHeatmapEnabled(heatmapEnabled)
            binding.heatmapToggleButton.text = if (heatmapEnabled) "HEATMAP: [ON]" else "HEATMAP: [OFF]"
        }

        logsAdapter = LogsAdapter(
            onClick = { run -> if (selectionMode) toggleRunSelection(run.id) else shareRun(run) },
            onLongClick = { run -> showRunOptions(run) },
            onUploadClick = { run -> uploadRun(run) },
        )
        recentRunsAdapter = LogsAdapter(
            onClick = { run -> showRunOptions(run) },
            onLongClick = { run -> showRunOptions(run) },
            onUploadClick = { run -> uploadRun(run) },
        )
        historicalAdapter = HistoricalAdapter(
            onHeaderClick = { label -> toggleHistGroup(label) },
            onRowClick = { point ->
                showDetailDialog(point.label.ifBlank { point.mac }, point.formatDetails(), point.mac)
            },
        )
        binding.logsList.layoutManager = LinearLayoutManager(this)
        binding.logsList.adapter = logsAdapter
        binding.recentRunsList.layoutManager = LinearLayoutManager(this)
        binding.recentRunsList.adapter = recentRunsAdapter
        binding.viewAllLogsButton.setOnClickListener { viewAllLogsOnMap() }
        binding.exportAllButton.setOnClickListener { showExportMenu() }
        binding.browseAllButton.setOnClickListener { toggleBrowseMode() }
        binding.selectModeButton.setOnClickListener { toggleSelectionMode() }
        binding.deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }

        binding.detailSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                detailSearchQuery = s?.toString() ?: ""
                adapter.submitList(buildGroupedFeed(detailFeedSources, detailFeedExtra))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.logsSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                logsSearchQuery = s?.toString() ?: ""
                refreshLogsView()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.importFloorPlanButton.setOnClickListener { floorPlanImagePicker.launch("image/*") }
        binding.switchFloorPlanButton.setOnClickListener { pickFloorPlan() }
        binding.deleteFloorPlanButton.setOnClickListener { deleteCurrentFloorPlan() }
        binding.floorPlanView.onImageTap = { xFrac, yFrac, hitIndex -> onFloorPlanTap(xFrac, yFrac, hitIndex) }
        loadMostRecentFloorPlan()

        setupNavigation()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDetailKind != DetailKind.NONE) {
                    hideDetail()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        requestNeededPermissions()
        ScanService.start(this)
        bindService(Intent(this, ScanService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        updateStatusText()
    }

    // ---- Navigation shell: Dashboard/WiFi/Bluetooth/Terminal bottom nav,
    // each of WiFi/Bluetooth being a menu of tool screens (mirrors Biscuit's
    // app layout) that open in a shared detail overlay on top. ----

    private fun setupNavigation() {
        binding.navDashboard.setOnClickListener { showSection(AppSection.DASHBOARD) }
        binding.navWifi.setOnClickListener { showSection(AppSection.TOOLS) }
        binding.navTerminal.setOnClickListener { showSection(AppSection.TERMINAL) }
        binding.navLogs.setOnClickListener { showDetail(DetailKind.LOGS, "Logs"); updateNavHighlight() }
        binding.detailBackButton.setOnClickListener { hideDetail() }
        binding.clearTerminalButton.setOnClickListener {
            terminalLines.clear()
            binding.terminalLog.text = "Cleared."
        }

        setupMenuRow(binding.menuLiveWifi, "Live WiFi", "WiFi networks detected this run") {
            openFeedDetail("Live WiFi", setOf(Source.RIG_WIFI, Source.PHONE_WIFI))
        }
        setupMenuRow(binding.menuBrowseLogs, "Browse Logs", "Past runs and saved sessions") {
            showDetail(DetailKind.LOGS, "Logs")
        }
        setupMenuRow(binding.menuPineapple, "Pineapple Detection", "Find possible WiFi Pineapple rogue APs") {
            openFeedDetail("Pineapple Detection", setOf(Source.RIG_WIFI, Source.PHONE_WIFI)) { it.isPineapple }
        }
        setupMenuRow(binding.menuAntennaCheck, "Antenna Check", "Compare antennas on a device's live signal (rig or phone)") {
            startActivity(AntennaActivity.intent(this))
        }
        setupMenuRow(binding.menuWatchlist, "Watchlist", "Get notified when chosen devices enter or leave range") {
            showWatchlistDialog()
        }

        val bleSources = setOf(Source.RIG_BLE, Source.PHONE_BLE)
        setupMenuRow(binding.menuLiveBle, "Live BLE", "Bluetooth devices detected this run") {
            openFeedDetail("Live BLE", bleSources)
        }
        setupMenuRow(binding.menuAirTag, "AirTag / SmartTag Detection", "Find possible Apple/Samsung trackers") {
            openFeedDetail("AirTag / SmartTag Detection", bleSources) { it.isTracker }
        }
        setupMenuRow(binding.menuFlipper, "Flipper Zero Detection", "Find nearby Flipper Zero devices") {
            openFeedDetail("Flipper Zero Detection", bleSources) { it.isFlipperZero }
        }
        setupMenuRow(binding.menuFlock, "Flock Safety Detection", "Find nearby Flock ALPR camera hardware") {
            openFeedDetail("Flock Safety Detection", bleSources) { it.isFlockCamera }
        }
        setupMenuRow(binding.menuSkimmer, "Skimmer Detection", "Find possible BLE credit-card skimmers") {
            openFeedDetail("Skimmer Detection", bleSources) { it.isSkimmer }
        }
        // Drones/mesh radios need service UUIDs, which only the phone's own
        // BLE scan carries (see Observation.isDrone's own comment) - filters
        // fine on bleSources since a rig-sourced observation just never has
        // the flag set true, same as isTracker's existing phone-only reach.
        setupMenuRow(binding.menuDrone, "Drone Detection", "Find nearby Remote ID drone broadcasts") {
            openFeedDetail("Drone Detection", bleSources) { it.isDrone }
        }
        setupMenuRow(binding.menuMeshRadio, "Mesh Radio Detection", "Find nearby Meshtastic devices") {
            openFeedDetail("Mesh Radio Detection", bleSources) { it.isMeshRadio }
        }
        setupMenuRow(binding.menuGlasses, "Smart Glasses Detection", "Find nearby Ray-Ban Meta glasses") {
            openFeedDetail("Smart Glasses Detection", bleSources) { it.isGlasses }
        }
        setupMenuRow(binding.menuActionCam, "Action Cam Detection", "Find nearby GoPro/Insta360 cameras") {
            openFeedDetail("Action Cam Detection", bleSources) { it.isActionCam }
        }
        setupMenuRow(binding.menuPoliceCam, "Police Cam Detection", "Find nearby Axon body/fleet cameras") {
            openFeedDetail("Police Cam Detection", bleSources) { it.isPoliceCam }
        }

        showSection(AppSection.DASHBOARD)
    }

    private fun setupMenuRow(row: ItemMenuRowBinding, title: String, subtitle: String, onClick: () -> Unit) {
        row.menuRowTitle.text = title.uppercase()
        row.menuRowSubtitle.text = subtitle
        row.root.setOnClickListener { onClick() }
    }

    private fun showSection(section: AppSection) {
        currentSection = section
        if (currentDetailKind != DetailKind.NONE) hideDetail()
        binding.dashboardContent.visibility = if (section == AppSection.DASHBOARD) View.VISIBLE else View.GONE
        binding.wifiMenuContent.visibility = if (section == AppSection.TOOLS) View.VISIBLE else View.GONE
        binding.terminalContent.visibility = if (section == AppSection.TERMINAL) View.VISIBLE else View.GONE
        updateNavHighlight()
        if (section == AppSection.DASHBOARD) refreshRecentRuns()
    }

    // Tactical "hardware function key" nav style: active = solid cyan fill
    // with inverted black text, inactive = transparent with a 1px slate
    // border and cyan text (2026-09-27 tactical cyberdeck overhaul).
    private fun setNavPillSelected(label: android.widget.TextView, selected: Boolean) {
        label.setTextColor(ContextCompat.getColor(this, if (selected) R.color.pure_black else R.color.cyan_500))
        label.setBackgroundResource(if (selected) R.drawable.bg_pill_cyan else R.drawable.bg_border_slate)
    }

    private fun updateNavHighlight() {
        // Only one tab pill lit at a time, matching the CYD dashboard's own
        // tab bar - a section's pill only counts as "active" when no detail
        // overlay (Logs/Map/etc, see showDetail()) is currently covering it.
        val noDetailOpen = currentDetailKind == DetailKind.NONE
        setNavPillSelected(binding.navDashboardLabel, currentSection == AppSection.DASHBOARD && noDetailOpen)
        setNavPillSelected(binding.navWifiLabel, currentSection == AppSection.TOOLS && noDetailOpen)
        setNavPillSelected(binding.navTerminalLabel, currentSection == AppSection.TERMINAL && noDetailOpen)
        // Logs isn't an AppSection (it's the same "detail" overlay Browse
        // Logs/Wardriving/Floor Plan already use, see showDetail()) - its nav
        // pill lights up based on currentDetailKind instead of currentSection.
        setNavPillSelected(binding.navLogsLabel, currentDetailKind == DetailKind.LOGS)
        // Settings opens its own screen rather than a section, so it's never the
        // "active" tab - but it still gets the same unselected pill styling
        // (slate border, accent text) as the others so it doesn't look odd.
        setNavPillSelected(binding.navSettingsLabel, false)
    }

    private fun openFeedDetail(title: String, sources: Set<Source>, extra: ((Observation) -> Boolean)? = null) {
        detailFeedSources = sources
        detailFeedExtra = extra
        detailSearchQuery = ""
        binding.detailSearchInput.setText("")
        showDetail(DetailKind.FEED, title)
    }

    private fun showDetail(kind: DetailKind, title: String) {
        currentDetailKind = kind
        binding.detailTitle.text = "> ${title.uppercase()} <"
        binding.detailContent.visibility = View.VISIBLE
        binding.detailFeedContent.visibility = if (kind == DetailKind.FEED) View.VISIBLE else View.GONE
        binding.mapTabContent.visibility = if (kind == DetailKind.MAP) View.VISIBLE else View.GONE
        binding.logsTabContent.visibility = if (kind == DetailKind.LOGS) View.VISIBLE else View.GONE
        binding.floorPlanTabContent.visibility = if (kind == DetailKind.FLOOR_PLAN) View.VISIBLE else View.GONE
        updateNavHighlight() // so the tab pills follow the open screen (TOOLS un-lights, LOGS lights up)
        syncPreviewScanning()
        when (kind) {
            DetailKind.FEED -> refreshDetailFeedIfShown()
            DetailKind.LOGS -> refreshLogsView()
            // The map sat GONE (zero size, not laid out) since launch if this
            // is the first time it's shown - osmdroid can end up with tiles
            // sitting in cache but never actually painted once the view
            // finally gets real dimensions, until something explicitly
            // kicks a fresh draw pass.
            DetailKind.MAP -> binding.mapView.post {
                binding.mapView.onResume()
                // If there's no run data or GPS fix to center on, fall back to the
                // phone's last known location, then the configured home, so the
                // live map opens on a real place instead of a blank ocean (0,0).
                if (!mapManager.isCentered()) {
                    val loc = scanService?.locationTracker?.lastLocation
                    if (loc != null) {
                        mapManager.ensureCentered(loc.latitude, loc.longitude)
                    } else {
                        val s = AppSettings(this)
                        if (s.homeLat != 0.0 || s.homeLon != 0.0) mapManager.ensureCentered(s.homeLat, s.homeLon)
                    }
                }
                binding.mapView.invalidate()
            }
            else -> {}
        }
    }

    private fun hideDetail() {
        currentDetailKind = DetailKind.NONE
        binding.detailContent.visibility = View.GONE
        updateNavHighlight()
        syncPreviewScanning()
    }

    // The live/detection tools (all the FEED detail screens) need the phone's
    // radios actually on to show anything. When one is open and no full run is
    // active, run a lightweight preview scan; otherwise leave the radios alone
    // (a real run drives them itself, and nothing should scan with no tool open).
    private fun syncPreviewScanning() {
        val s = scanService ?: return
        if (currentDetailKind == DetailKind.FEED && !s.running) {
            // Only power up the radio the open tool actually needs, so it
            // "searches for the selected tool" - a WiFi tool doesn't spin up BLE.
            val src = detailFeedSources
            val wifi = Source.PHONE_WIFI in src || Source.RIG_WIFI in src
            val ble = Source.PHONE_BLE in src || Source.RIG_BLE in src
            val cell = Source.PHONE_CELL in src
            s.startPreview(wifi = wifi, ble = ble, cell = cell)
        } else {
            s.stopPreview()
        }
    }

    private fun refreshDetailFeedIfShown() {
        if (currentDetailKind != DetailKind.FEED) return
        val list = buildGroupedFeed(detailFeedSources, detailFeedExtra)
        adapter.submitList(list)
        binding.detailFeedEmptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            val s = scanService
            binding.detailFeedEmptyText.text = when {
                s == null -> "Starting up…"
                !hasLocationPermission() -> "Grant Location to this app so it can scan, then reopen this tool."
                s.running || s.previewing -> "Searching… long-press a result to fox-hunt it."
                else -> "Nothing detected yet."
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun refreshRecentRuns() {
        Thread {
            val runs = dao.allRunSummaries().take(5)
            runOnUiThread {
                recentRunsAdapter.submitList(runs)
                recentRunsAdapter.setSelectionState(false, emptySet())
                binding.recentRunsEmptyText.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // Set when the widget or quick-settings tile opened us to start a run - see
    // ScanService.toggleRun() for why they can't start it themselves.
    private var startRunRequested = false

    private fun maybeStartRunFromShortcut() {
        val s = scanService ?: return
        if (!startRunRequested) return
        startRunRequested = false
        if (!s.running) {
            s.startRun(notifyRig = true, reason = "tile/widget/notification START")
            binding.startStopButton.text = "> STOP"
            binding.pauseResumeButton.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ScanService.ACTION_START) {
            startRunRequested = true
            maybeStartRunFromShortcut()
        }
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            scanService?.connectRig()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        huntDialog?.dismiss() // avoid a WindowLeaked crash if the app closes mid-hunt
        // Deliberately does NOT stop the run - the whole point of
        // ScanService is that closing this Activity (screen lock, app
        // switch, task swipe) must not interrupt an active run.
        scanService?.listener = null
        unbindService(serviceConnection)
    }

    // osmdroid's own lifecycle hooks - releases/reclaims its tile-fetch
    // resources alongside the activity instead of leaking them.
    // Status that changes without an event to hang a redraw on (rig health, fix freshness,
    // battery) - refreshed every 2s while the screen is visible.
    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatusText()
            binding.root.postDelayed(this, 2_000)
        }
    }

    override fun onResume() {
        super.onResume()
        // Reclaim the service listener every time we come to the foreground. It's
        // shared: another screen (e.g. Antenna check) becomes the listener while
        // it's up and nulls it on the way out, and onServiceConnected only fires
        // on a fresh bind - so without this the live feed silently stops updating
        // after visiting one of those screens ("Live WiFi doesn't work").
        scanService?.let {
            it.listener = this
            for (map in it.groups.values) for (obs in map.values) mapManager.upsertLive(obs)
        }
        refreshDetailFeedIfShown()
        binding.mapView.onResume()
        refreshAccountStats()
        refreshLifetimeChips()
        binding.root.removeCallbacks(statusTicker)
        binding.root.postDelayed(statusTicker, 2_000)
        syncPreviewScanning() // resume live-tool scanning if one is still open
    }

    // wdgwars/WiGLE account totals - not per-observation, so fetched on a
    // background thread on resume (and again after an upload, when they'd
    // actually have changed) rather than tracked live like the session
    // counts are.
    private fun refreshAccountStats() {
        val wigleToken = appSettings.wigleToken
        val wdgwarsKey = appSettings.wdgwarsKey
        if (wigleToken.isBlank() && wdgwarsKey.isBlank()) {
            binding.accountStatsStatus.visibility = View.GONE
            return
        }
        Thread {
            val wdg = AccountStats.fetchWdgwars(wdgwarsKey)
            val wig = AccountStats.fetchWigle(wigleToken)
            runOnUiThread {
                val parts = mutableListOf<String>()
                wdg?.let { parts.add("WDGWars ${fmtCount(it.total)} pts") }
                wig?.let { parts.add("WiGLE new ${fmtCount(it.discoveredWifi)}") }
                if (parts.isNotEmpty()) {
                    binding.accountStatsStatus.text = parts.joinToString("  ·  ")
                    binding.accountStatsStatus.visibility = View.VISIBLE
                } else {
                    binding.accountStatsStatus.visibility = View.GONE
                }
            }
        }.start()
    }

    // Real lifetime counters for the dashboard's bottom chip row - see
    // WardriveDao's Analytics section for the underlying queries.
    private fun refreshLifetimeChips() {
        Thread {
            val runs = dao.totalRunCount()
            val wifi = dao.lifetimeWifiCount()
            val bt = dao.lifetimeBleCount()
            runOnUiThread {
                binding.lifetimeRunsChip.text = "${fmtCount(runs)} RUNS"
                binding.lifetimeWifiChip.text = "${fmtCount(wifi)} WIFI"
                binding.lifetimeBtChip.text = "${fmtCount(bt)} BT"
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        binding.root.removeCallbacks(statusTicker)
        // Don't keep the radios on scanning in the background just for a live
        // tool - preview stops here (a real run keeps going; stopPreview no-ops
        // while running). onResume restarts it if the tool is still open.
        scanService?.stopPreview()
    }

    // ---- Permissions ----

    private fun requestNeededPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS) // so the foreground-service notification actually shows
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---- ScanService.SessionListener ----

    override fun onRigConnected() {
        updateStatusText()
    }

    override fun onRigDisconnected() {
        updateStatusText()
    }

    override fun onMeshLinkStateChanged(state: RigLinkManager.MeshLinkState) {
        updateStatusText()
    }

    override fun onRunStateChanged(running: Boolean) {
        binding.startStopButton.text = if (running) "> STOP" else "> START"
        binding.pauseResumeButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.pauseResumeButton.text = "> PAUSE"
        if (running) {
            adapter.clear()
            mapManager.clearLive()
        }
        updateStatusText()
    }

    override fun onPauseStateChanged(paused: Boolean) {
        binding.pauseResumeButton.text = if (paused) "> RESUME" else "> PAUSE"
        updateStatusText()
    }

    override fun onObservation(tagged: Observation) {
        huntDialog?.let { if (it.matches(tagged.mac)) it.onSample(tagged) }
        mapManager.upsertLive(tagged)
        refreshDetailFeedIfShown()
        updateStatusText()
    }

    override fun onLocationChanged(location: android.location.Location) {
        mapManager.addTrailPoint(location.latitude, location.longitude)
    }

    // Raw rig USB traffic (RX from the rig, TX for commands we send) - shown
    // verbatim in the Terminal tab. Capped so a long session doesn't grow
    // this without bound; the rig's own periodic status lines land here too,
    // not just wdstream traffic, since RigLinkManager forwards every WD:
    // line, not just ones wdstream mode gates.
    override fun onRigLogLine(line: String) {
        terminalLines.addLast(line)
        while (terminalLines.size > 200) terminalLines.removeFirst()
        binding.terminalLog.text = terminalLines.joinToString("\n")
        binding.terminalScroll.post { binding.terminalScroll.fullScroll(View.FOCUS_DOWN) }

        // The rig announces its service-mode URL (log web server) as
        // "WD:SERVICE on ip=<addr> [pw=<code>]" the moment it joins WiFi - pop the
        // URL (and the one-time password, if the rig generated one).
        if (line.contains("WD:SERVICE on ip=")) {
            val ip = line.substringAfter("ip=").trim().substringBefore(' ')
            val pw = if (line.contains("pw=")) line.substringAfter("pw=").trim().substringBefore(' ') else ""
            if (ip.isNotEmpty()) showServiceModeDialog(ip, pw)
        } else if (line.contains("WD:SERVICE off")) {
            serviceModeDialog?.dismiss(); serviceModeDialog = null
        } else if (line.contains("WD:SERVICE error=")) {
            val why = line.substringAfter("error=").trim()
            Toast.makeText(this, "Service mode failed: ${if (why == "nowifi") "no WiFi in the rig's config.cfg" else "couldn't join WiFi"}", Toast.LENGTH_LONG).show()
        }
    }

    private var serviceModeDialog: AlertDialog? = null

    private fun showServiceModeDialog(ip: String, pw: String = "") {
        serviceModeDialog?.dismiss()
        serviceModeDialog = AlertDialog.Builder(this)
            .setTitle("Rig service mode")
            .setMessage(
                "The rig is on WiFi at:\n\nhttp://$ip\n\n" +
                    "Open that in a browser on the same network to download the session CSV logs.\n\n" +
                    (if (pw.isNotEmpty()) "Login: user \"wardrive\", password $pw\n\n" else "Login: user \"wardrive\" and your service_password.\n\n") +
                    "Tap the rig's screen, or Exit below, to return to normal scanning."
            )
            .setPositiveButton("Open in browser") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip"))) } catch (_: Exception) {}
            }
            .setNegativeButton("Exit service mode") { _, _ ->
                scanService?.rigLink?.sendRaw("rig service off")
            }
            .setNeutralButton("Close", null)
            .show()
    }

    override fun onPersistentTrackerAlert(tagged: Observation, previousSighting: HistoricalPoint) {
        val movedM = distanceMeters(tagged.lat, tagged.lon, previousSighting.lat, previousSighting.lon)
        val name = tagged.label.ifBlank { tagged.mac }
        AlertDialog.Builder(this)
            .setTitle("⚠ Possible tracker following you")
            .setMessage(
                "\"$name\" (${tagged.mac}) was flagged as a possible AirTag/SmartTag, and was also logged " +
                    "in an earlier run about %.1f km away from where it was last seen (${previousSighting.firstSeen}).\n\n".format(movedM / 1000) +
                    "A tracker showing up again this far from its last known spot can mean it's traveling with " +
                    "you rather than just being a fixed device you happened to pass twice. This is a best-effort " +
                    "heuristic, not a certainty - see the app's own notes on tracker detection."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // Shared by the map-marker tap and the Logs-tab historical row tap -
    // same detail dialog either way, now with a one-tap MAC copy.
    private fun showDetailDialog(title: String, body: String, mac: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy MAC") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MAC", mac))
                Toast.makeText(this, "Copied $mac", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // "This run" only - no cross-session lookups (new-vs-previously-seen,
    // most common SSID across all logs) yet, that needs the same
    // infrastructure as the not-yet-built device-return-tracking feature.
    // Everything here comes straight from data already held in memory, so
    // it's synchronous - no loading state needed.
    private fun showSessionStats() {
        val service = scanService
        if (service == null) {
            Toast.makeText(this, "Not connected to the scan service yet", Toast.LENGTH_SHORT).show()
            return
        }
        val groups = service.groups
        // Merge by mac (not a flat concat) - the rig and the phone each run
        // their own radio, so the same real device can land in both
        // Source-keyed maps. A plain flatMap would double-count every
        // device both radios caught; merging maps on the shared mac key
        // collapses that back to one entry per real device.
        val wifiObs = (groups.getValue(Source.RIG_WIFI) + groups.getValue(Source.PHONE_WIFI)).values
        val bleObs = (groups.getValue(Source.RIG_BLE) + groups.getValue(Source.PHONE_BLE)).values
        val allObs = wifiObs + bleObs + groups.getValue(Source.PHONE_CELL).values
        val totalUnique = allObs.size
        val distanceMiles = service.totalDistanceMeters / 1609.34
        val elapsedMin = if (service.runStartMs > 0) (System.currentTimeMillis() - service.runStartMs) / 60000.0 else 0.0
        val authCounts = wifiObs.groupingBy { it.authOrType.uppercase() }.eachCount()
        val returningCount = allObs.count { it.isReturning }
        val channelCounts = wifiObs.filter { it.channel > 0 }.groupingBy { it.channel }.eachCount()
        val cellObs = groups.getValue(Source.PHONE_CELL).values
        val carrierCounts = cellObs.mapNotNull { CarrierLookup.carrierFor(it.mac) }.groupingBy { it }.eachCount()

        val body = buildString {
            appendLine("Rig WiFi: ${groups.getValue(Source.RIG_WIFI).size}  ·  Rig BLE: ${groups.getValue(Source.RIG_BLE).size}")
            appendLine("Phone WiFi: ${groups.getValue(Source.PHONE_WIFI).size}  ·  Phone BLE: ${groups.getValue(Source.PHONE_BLE).size}")
            appendLine("Cell towers: ${groups.getValue(Source.PHONE_CELL).size}")
            appendLine("Total unique devices: $totalUnique")
            appendLine("New this run: ${totalUnique - returningCount}  ·  Seen before: $returningCount")
            appendLine()
            appendLine("Duration: %.0f min".format(elapsedMin))
            appendLine("Distance driven: %.2f mi (%.2f km)".format(distanceMiles, service.totalDistanceMeters / 1000))
            if (distanceMiles > 0.05) appendLine("Discovery rate: %.1f devices/mile".format(totalUnique / distanceMiles))
            if (wifiObs.isNotEmpty()) {
                appendLine()
                appendLine("WiFi security:")
                for ((auth, count) in authCounts.entries.sortedByDescending { it.value }) {
                    appendLine("  $auth: $count (${count * 100 / wifiObs.size}%)")
                }
            }
            if (channelCounts.isNotEmpty()) {
                appendLine()
                appendLine("Busiest WiFi channels:")
                for ((channel, count) in channelCounts.entries.sortedByDescending { it.value }.take(5)) {
                    appendLine("  ch$channel: $count network${if (count == 1) "" else "s"}")
                }
            }
            if (carrierCounts.isNotEmpty()) {
                appendLine()
                appendLine("Carriers seen:")
                for ((carrier, count) in carrierCounts.entries.sortedByDescending { it.value }) {
                    appendLine("  $carrier: $count")
                }
            }
        }.trim()

        AlertDialog.Builder(this).setTitle("Session Stats").setMessage(body).setPositiveButton("Close", null).show()
    }

    // Everything you've ever logged, across every saved run - unlike
    // Session Stats this needs to read every observation from the DB, so
    // it's Thread-backed instead of synchronous. Vendor/carrier lookups
    // reuse the same offline tables live observations use, applied here to
    // historical points instead.
    private fun showAllTimeStats() {
        Toast.makeText(this, "Crunching every log…", Toast.LENGTH_SHORT).show()
        Thread {
            val points = dao.latestPerMac().map { it.toHistoricalPoint() }
            val wifiPoints = points.filter { it.type.equals("WIFI", ignoreCase = true) }
            val blePoints = points.filter { it.type.equals("BLE", ignoreCase = true) }
            val cellPoints = points.filter { !it.type.equals("WIFI", ignoreCase = true) && !it.type.equals("BLE", ignoreCase = true) }

            val ssidCounts = wifiPoints.filter { it.label.isNotBlank() }.groupingBy { it.label }.eachCount()
            val vendorCounts = points.mapNotNull { OuiLookup.vendorFor(it.mac) }.groupingBy { it }.eachCount()
            val carrierCounts = cellPoints.mapNotNull { CarrierLookup.carrierFor(it.mac) }.groupingBy { it }.eachCount()

            val body = buildString {
                appendLine("Unique WiFi networks: ${wifiPoints.size}")
                appendLine("Unique BLE devices: ${blePoints.size}")
                appendLine("Unique cell towers: ${cellPoints.size}")
                appendLine("Total: ${points.size}")
                if (ssidCounts.isNotEmpty()) {
                    appendLine()
                    appendLine("Most common SSID names:")
                    for ((ssid, count) in ssidCounts.entries.sortedByDescending { it.value }.take(5)) {
                        appendLine("  \"$ssid\": $count")
                    }
                }
                if (vendorCounts.isNotEmpty()) {
                    appendLine()
                    appendLine("Top vendors:")
                    for ((vendor, count) in vendorCounts.entries.sortedByDescending { it.value }.take(5)) {
                        appendLine("  $vendor: $count")
                    }
                }
                if (carrierCounts.isNotEmpty()) {
                    appendLine()
                    appendLine("Carriers ever seen:")
                    for ((carrier, count) in carrierCounts.entries.sortedByDescending { it.value }) {
                        appendLine("  $carrier: $count")
                    }
                }
            }.trim()

            runOnUiThread {
                AlertDialog.Builder(this).setTitle("All-Time Stats").setMessage(body).setPositiveButton("Close", null).show()
            }
        }.start()
    }

    // Fox-hunting: long-press a row to track that one device's live signal.
    // Only one hunt at a time - starting a new one replaces whatever was
    // showing. See HuntDialog for why this is signal-strength feedback, not
    // triangulation. Antenna check lives only in Settings, so long-press goes
    // straight into the hunt rather than offering a picker.
    private fun startHunt(obs: Observation) {
        huntDialog?.dismiss()
        val dialog = HuntDialog(this, obs.mac, obs.label)
        dialog.setOnDismissListener { huntDialog = null }
        huntDialog = dialog
        dialog.show()
        dialog.onSample(obs) // seed it with the reading that triggered the long-press
    }

    private data class KnownDev(val display: String, val key: String, val label: String, val isWifi: Boolean, val nearby: Boolean)

    // Devices currently/recently heard (rig + phone, deduped by MAC, strongest
    // first) - the live part of the Watchlist picker. The picker also appends
    // devices from the log history (knownDevicesForWatch) so you can watch one
    // that isn't in range right now.
    private fun knownDevices(): List<KnownDev> {
        val s = scanService ?: return emptyList()
        val best = LinkedHashMap<String, Observation>()
        for (map in s.groups.values) for ((mac, obs) in map) {
            val cur = best[mac]
            if (cur == null || obs.rssi > cur.rssi) best[mac] = obs
        }
        return best.values.sortedByDescending { it.rssi }.map { obs ->
            val label = obs.label.ifBlank { "(hidden)" }
            val isWifi = obs.source == Source.RIG_WIFI || obs.source == Source.PHONE_WIFI
            KnownDev("$label  ·  ${obs.mac}  ·  ${obs.rssi} dBm", obs.mac, label, isWifi, nearby = true)
        }
    }

    // The Watchlist tool: search/filter the nearby devices, tap + to watch one,
    // each with its own "In"/"Out" notification toggles. ScanService reads the
    // list live, so changes apply on the next sighting without restarting a run.
    private fun showWatchlistDialog() {
        val s = AppSettings(this)
        val entries = s.watchEntries().toMutableList()
        // Live devices first; the log-history ones get appended below.
        val all = knownDevices().toMutableList()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "Watch specific devices and get notified when they enter / leave range. The list shows what's nearby now plus devices from your logs, so you can watch one even when it isn't in range. Scanning (a run or an open live tool) must be on for the notification to fire."
            setTextColor(secondary); textSize = 12f
        })

        // Watched devices on top, then the searchable/filterable pool to add from.
        root.addView(android.widget.TextView(this).apply { text = "WATCHING"; setTextColor(secondary); textSize = 11f; setPadding(0, dp(8), 0, dp(2)) })
        val watchContainer = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        root.addView(watchContainer)

        // --- search + type filter ---
        val search = EditText(this).apply { hint = "Search by name or MAC"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val typeFilter = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("All", "WiFi", "BLE"))
        }
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
            addView(search, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(typeFilter)
        })

        val availHeader = android.widget.TextView(this).apply { text = "DEVICES"; setTextColor(secondary); textSize = 11f; setPadding(0, dp(4), 0, dp(2)) }
        root.addView(availHeader)
        val availContainer = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        root.addView(availContainer)

        fun rebuildAll() {
            // Watched entries, each with its own toggles + remove.
            watchContainer.removeAllViews()
            if (entries.isEmpty()) {
                watchContainer.addView(android.widget.TextView(this).apply {
                    text = "No devices watched yet - tap + on one above."; setTextColor(secondary); setPadding(0, dp(4), 0, 0)
                })
            } else for (i in entries.indices) {
                val e = entries[i]
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(android.widget.TextView(this).apply { text = e.label; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(primary) },
                    android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(android.widget.CheckBox(this).apply {
                    text = "In"; isChecked = e.enter
                    setOnCheckedChangeListener { _, c -> entries[i] = entries[i].copy(enter = c) }
                })
                row.addView(android.widget.CheckBox(this).apply {
                    text = "Out"; isChecked = e.leave
                    setOnCheckedChangeListener { _, c -> entries[i] = entries[i].copy(leave = c) }
                })
                row.addView(android.widget.Button(this).apply {
                    text = "✕"
                    minWidth = 0; minimumWidth = 0; setPadding(dp(20), 0, dp(20), 0)
                    setOnClickListener { entries.removeAt(i); rebuildAll() }
                })
                watchContainer.addView(row)
            }

            // Nearby pool, filtered by search text + type, excluding already-watched.
            availContainer.removeAllViews()
            val q = search.text.toString().trim()
            val type = typeFilter.selectedItemPosition // 0 all, 1 wifi, 2 ble
            val matches = all.filter { kd ->
                entries.none { it.key.equals(kd.key, ignoreCase = true) } &&
                    (type == 0 || (type == 1) == kd.isWifi) &&
                    (q.isEmpty() || kd.display.contains(q, ignoreCase = true))
            }.take(50)
            if (matches.isEmpty()) {
                availContainer.addView(android.widget.TextView(this).apply {
                    text = if (all.isEmpty()) "No devices yet - start scanning or log a run." else "No matches."
                    setTextColor(secondary); setPadding(0, dp(4), 0, 0)
                })
            } else for (kd in matches) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(android.widget.TextView(this).apply { text = kd.display; textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(primary) },
                    android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(android.widget.Button(this).apply {
                    text = "+"
                    minWidth = 0; minimumWidth = 0; setPadding(dp(20), 0, dp(20), 0)
                    setOnClickListener {
                        entries.add(AppSettings.WatchEntry(kd.key, kd.label, enter = true, leave = true))
                        rebuildAll()
                    }
                })
                availContainer.addView(row)
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(sx: android.text.Editable?) = rebuildAll()
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, cc: Int) {}
        })
        typeFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = rebuildAll()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        rebuildAll()

        // Append devices from the log history (off the main thread) so you can
        // watch one that isn't in range now. Live rows win, so skip any MAC
        // already present from the live scan.
        Thread {
            val known = dao.knownDevicesForWatch()
            runOnUiThread {
                val have = all.mapTo(HashSet()) { it.key.lowercase() }
                for (row in known) {
                    if (!have.add(row.mac.lowercase())) continue
                    val label = row.label.ifBlank { "(hidden)" }
                    val isWifi = row.type == "WIFI"
                    all.add(KnownDev("$label  ·  ${row.mac}  ·  seen before", row.mac, label, isWifi, nearby = false))
                }
                rebuildAll()
            }
        }.start()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Watchlist")
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton("Done") { _, _ ->
                s.setWatchEntries(entries)
                Toast.makeText(this, "Watchlist saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Tap a row for the full picture on one device - everything the live
    // feed and the row subtitle don't have room for. Reuses showDetailDialog
    // (already wired for map-marker taps) rather than a new dialog type.
    private fun showObservationDetail(obs: Observation) {
        val title = obs.label.ifBlank { "(hidden)" }
        val body = buildString {
            appendLine("IDENTITY")
            appendLine("MAC: ${obs.mac}")
            if (obs.vendor != null) appendLine("Vendor: ${obs.vendor}")
            else if (obs.macRandomized) appendLine("Vendor: — randomized MAC")

            appendLine()
            appendLine("SIGNAL")
            appendLine("Current: ${obs.rssi} dBm")
            appendLine("Strongest this run: ${obs.bestRssi} dBm")
            appendLine("Proximity: ${proximityLabel(obs.bestRssi)}")
            appendLine("Seen ${obs.timesSeenThisRun} time${if (obs.timesSeenThisRun == 1) "" else "s"} this run")

            when (obs.source) {
                Source.RIG_WIFI, Source.PHONE_WIFI -> {
                    appendLine()
                    appendLine("WIFI")
                    appendLine("Security: ${obs.authOrType}")
                    if (obs.channel > 0) appendLine("Channel: ${obs.channel}")
                    if (obs.frequencyMHz > 0) appendLine("Frequency: ${obs.frequencyMHz} MHz (${bandLabel(obs.frequencyMHz)})")
                    if (obs.channelWidthMHz > 0) appendLine("Width: ${obs.channelWidthMHz} MHz")
                    appendLine("Hidden: ${if (obs.hidden) "Yes" else "No"}")
                }
                Source.RIG_BLE, Source.PHONE_BLE -> {
                    appendLine()
                    appendLine("BLUETOOTH")
                    obs.companyId?.let { appendLine("Company ID: 0x${it.toString(16).uppercase().padStart(4, '0')} ($it)") }
                    appendLine("Connectable: ${if (obs.connectable) "Yes" else "No"}")
                    if (obs.serviceUuids.isNotEmpty()) {
                        appendLine("Service UUIDs:")
                        obs.serviceUuids.forEach { appendLine("  $it") }
                    }
                }
                Source.PHONE_CELL -> {
                    appendLine()
                    appendLine("CELL TOWER")
                    appendLine("Radio: ${obs.authOrType}")
                    if (obs.rsrp != 0) appendLine("RSRP: ${obs.rsrp} dBm")
                    if (obs.rsrq != 0) appendLine("RSRQ: ${obs.rsrq} dB")
                    appendLine("Identity: ${obs.mac}")
                }
            }

            val flags = buildList {
                if (obs.isTracker) add("⚠ Possible tracker (AirTag/SmartTag-style)")
                if (obs.isFlipperZero) add("⚡ Flipper Zero signature")
                if (obs.isFlockCamera) add("📷 Flock Safety camera signature")
                if (obs.isSkimmer) add("💳 Possible BLE card-skimmer module")
            }
            if (flags.isNotEmpty()) {
                appendLine()
                appendLine("FLAGS")
                flags.forEach { appendLine(it) }
            }

            appendLine()
            appendLine("ACTIVITY")
            appendLine("First seen: ${obs.firstSeenIso}")
            appendLine(if (obs.isReturning) "Also seen in an earlier run" else "New this run")

            if (obs.lat != 0.0 || obs.lon != 0.0) {
                appendLine()
                appendLine("LOCATION")
                appendLine("Coordinates: %.6f, %.6f".format(obs.lat, obs.lon))
                if (obs.altitudeM != 0.0) appendLine("Altitude: %.1f m".format(obs.altitudeM))
                if (obs.accuracyM != 0.0) appendLine("Accuracy: ±%.0f m".format(obs.accuracyM))
            }
        }.trim()

        showDetailDialog(title, body, obs.mac)
    }

    // Same Hot/Warm/Cold buckets a human would eyeball off the number -
    // clamped to a realistic dBm range first so a wildly out-of-range
    // reading can't produce a nonsense bucket.
    private fun proximityLabel(rssi: Int): String {
        val clamped = rssi.coerceIn(-95, -50)
        val s = (clamped + 95) / 45f
        return when {
            s > 0.66f -> "Hot (close)"
            s > 0.40f -> "Warm"
            else -> "Cold (far)"
        }
    }

    private fun bandLabel(freqMHz: Int): String = when {
        freqMHz in 2400..2500 -> "2.4 GHz"
        freqMHz in 4900..5900 -> "5 GHz"
        freqMHz in 5925..7125 -> "6 GHz"
        else -> "?"
    }

    // sources/extra let the same feed drive six different detail screens
    // (Live WiFi, Live BLE, AirTag/Flipper/Flock/Skimmer detection) - each
    // just picks a different subset of Source and an optional extra
    // predicate over Observation's own flags, instead of duplicating this
    // whole build+group+search pipeline per screen.
    private fun buildGroupedFeed(
        sources: Set<Source> = groupOrder.toSet(),
        extra: ((Observation) -> Boolean)? = null,
    ): List<FeedItem> {
        val groups = scanService?.groups ?: return emptyList()
        val query = detailSearchQuery.trim()
        val out = mutableListOf<FeedItem>()
        for (source in groupOrder) {
            if (source !in sources) continue
            val map = groups.getValue(source)
            var matches = if (query.isEmpty()) map.values.toList() else map.values.filter { matchesQuery(it.label, it.mac, query) }
            if (extra != null) matches = matches.filter(extra)
            if (matches.isEmpty()) continue
            // Searching force-expands every group with a hit, so results
            // are visible immediately instead of needing a tap per group -
            // manual collapse/expand state resumes once the search clears.
            val expanded = if (query.isNotEmpty()) true else (expandedGroups[source] ?: false)
            out.add(FeedItem.Header(source, source.label, matches.size, expanded))
            if (expanded) {
                out.addAll(matches.reversed().map { FeedItem.Row(it) }) // most-recently-discovered first within the group (repeat sightings update in place, not position)
            }
        }
        return out
    }

    private fun matchesQuery(label: String, mac: String, query: String): Boolean =
        label.contains(query, ignoreCase = true) || mac.contains(query, ignoreCase = true)

    private fun updateStatusText() {
        val service = scanService

        val rigConnected = service?.rigConnected == true
        binding.rigDot.setTextColor(ContextCompat.getColor(this, if (rigConnected) R.color.green_ok else R.color.red_error))
        val linkType = service?.rigLink?.connectionType
        binding.telemetryLinkText.text = when {
            rigConnected -> " CYD: ${linkType ?: "UP"}"
            AppSettings(this).pairedRigAddress.isEmpty() -> " CYD: PAIR"
            else -> " CYD: DOWN"
        }
        binding.telemetryLinkText.setTextColor(ContextCompat.getColor(this, if (rigConnected) R.color.cyan_500 else R.color.text_secondary))

        // Only meaningful once the phone's own USB link to cyd_node is up - with no link at all
        // there's no way to know whether the rest of the rig is reachable, so hide it rather than
        // show a stale or meaningless DOWN.
        binding.meshDot.visibility = if (rigConnected) View.VISIBLE else View.GONE
        binding.telemetryMeshText.visibility = if (rigConnected) View.VISIBLE else View.GONE
        if (rigConnected) {
            val meshState = service?.meshLinkState ?: RigLinkManager.MeshLinkState.DISCONNECTED
            val (meshLabel, meshColorRes) = when (meshState) {
                RigLinkManager.MeshLinkState.CONNECTED -> " RIG: UP" to R.color.cyan_500
                RigLinkManager.MeshLinkState.CONNECTING -> " RIG: WAIT" to R.color.purple_500
                RigLinkManager.MeshLinkState.DISCONNECTED -> " RIG: DOWN" to R.color.red_error
            }
            binding.telemetryMeshText.text = meshLabel
            binding.telemetryMeshText.setTextColor(ContextCompat.getColor(this, meshColorRes))
            binding.meshDot.setTextColor(ContextCompat.getColor(this, meshColorRes))
        }

        // "Fix" = a position fresh enough to log with, not just one seen at some point.
        val gpsFix = service?.locationTracker?.hasFix() == true
        val running = service?.running == true
        // While a run is active with no fix, sightings are still gathered but saved
        // without a position (0,0) - kept locally, never uploaded/exported. Warn in
        // amber so it's obvious that data is landing without coordinates.
        if (running && !gpsFix) {
            val noFix = service?.noFixCountThisRun ?: 0
            binding.gpsDot.text = if (noFix > 0) "NO GPS FIX - $noFix saved w/o position (not uploaded)"
                                  else "NO GPS FIX - logging without position (not uploaded)"
            binding.gpsDot.setTextColor(ContextCompat.getColor(this, R.color.amber_warning))
        } else {
            binding.gpsDot.text = if (gpsFix) "PHONE GPS: FIX" else "PHONE GPS: ---"
            binding.gpsDot.setTextColor(ContextCompat.getColor(this, if (gpsFix) R.color.cyan_500 else R.color.text_secondary))
        }

        val channel = service?.lastRigChannel ?: 0
        binding.telemetryChannel.text = if (channel > 0) "CH: %02d".format(channel) else "CH: --"

        val scanning = service?.running == true
        binding.telemetryScanStatus.text = "STATUS: " + if (scanning) "SCANNING" else "STOPPED"
        binding.telemetryScanStatus.setTextColor(ContextCompat.getColor(this, if (scanning) R.color.cyan_500 else R.color.text_secondary))

        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckMs > BATTERY_CHECK_INTERVAL_MS) {
            lastBatteryCheckMs = now
            val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryPct = batteryStatus?.let {
                val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) (level * 100 / scale) else null
            }
            val charging = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1).let {
                it == android.os.BatteryManager.BATTERY_STATUS_CHARGING || it == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
            lastBatteryText = if (batteryPct != null) "PWR: $batteryPct%${if (charging) " CHG" else ""}" else "PWR: --%"
        }
        binding.telemetryPower.text = lastBatteryText

        val groups = service?.groups
        if (groups == null) {
            val foundMode = appSettings.rigCountFoundMode
            binding.wigleCountLabel.text = if (foundMode) "APS" else "WIGLE"
            binding.wdgwCountLabel.text = if (foundMode) "BT" else "WDGW"
            binding.btCountLabel.text = if (foundMode) "ALL" else "BT"
            binding.wigleCountBig.text = "0"
            binding.wdgwCountBig.text = "0"
            binding.btCountBig.text = "0"
            binding.cellCountBig.text = "0"
            binding.countsStatus.text = "RIG WIFI 0 · RIG BT 0 · PHONE WIFI 0 · PHONE BT 0"
            binding.totalStatus.text = "TOTAL: 0"
            updateMapStatusOverlay(service, 0, 0, 0)
        } else {
            val rigWifi = groups.getValue(Source.RIG_WIFI).size
            val rigBle = groups.getValue(Source.RIG_BLE).size
            val phoneWifi = groups.getValue(Source.PHONE_WIFI).size
            val phoneBle = groups.getValue(Source.PHONE_BLE).size
            val cell = groups.getValue(Source.PHONE_CELL).size
            // The headline numbers come from ScanService.wifiCountThisRun /
            // bleCountThisRun: a union of MACs (never a raw sum - the rig and the
            // phone can both catch the same AP), shared with the maps overlay and
            // pushed to the rig so the CYD shows the same figures.
            // Same WIGLE/WDGW split as the rig's own CYD dashboard: WIGLE is
            // WiFi-only (matching the WigleWifi CSV format), WDGW is the
            // "everything" gateway feed (WiFi+BLE combined) - see cyd_node's
            // drawTabMain() for the original design this mirrors.
            val wifiShown = service.wifiCountThisRun
            val bleShown = service.bleCountThisRun
            // One setting (Settings -> counters) switches the dashboard, the CYD screen and
            // the map overlay together: WIGLE / WDGW / BT, or the found-device view
            // APS / BT / ALL in the same order the CYD draws it.
            if (appSettings.rigCountFoundMode) {
                binding.wigleCountLabel.text = "APS"; binding.wigleCountBig.text = "$wifiShown"
                binding.wdgwCountLabel.text = "BT"; binding.wdgwCountBig.text = "$bleShown"
                binding.btCountLabel.text = "ALL"; binding.btCountBig.text = "${wifiShown + bleShown}"
            } else {
                binding.wigleCountLabel.text = "WIGLE"; binding.wigleCountBig.text = "$wifiShown"
                binding.wdgwCountLabel.text = "WDGW"; binding.wdgwCountBig.text = "${wifiShown + bleShown}"
                binding.btCountLabel.text = "BT"; binding.btCountBig.text = "$bleShown"
            }
            binding.cellCountBig.text = "$cell"
            val health = service.rigHealth?.takeIf { service.rigConnected && System.currentTimeMillis() - it.atMs < 10_000 }
            // The rig's own totals (its ESP32 and CYD scanners combined - the same numbers as the
            // CYD screen) when it reports them; otherwise what the phone has received from it.
            val rigWifiShown = health?.rigWifi ?: rigWifi
            val rigBleShown = health?.rigBle ?: rigBle
            binding.countsStatus.text = "RIG WIFI $rigWifiShown · RIG BT $rigBleShown · " +
                "PHONE WIFI $phoneWifi · PHONE BT $phoneBle"

            binding.rigHealthText.visibility = if (health != null) View.VISIBLE else View.GONE
            if (health != null) {
                val gps = if (health.gpsFix) "RIG GPS FIX" + (if (health.sats >= 0) " ${health.sats} SATS" else "") else "RIG GPS: NO FIX"
                val sd = if (health.sdOk) "SD OK" else "SD FAIL"
                val sdFree = health.sdFreeMB?.takeIf { it >= 0 }?.let { " (${fmtStorage(it)} FREE)" } ?: ""
                val pend = if (health.pendingUploads > 0) " · ${health.pendingUploads} TO UPLOAD (TAP)" else ""
                // Second telemetry line: main node link, free RAM. Only shown on
                // firmware new enough to report them, so older rigs read unchanged.
                // "Main node" is wifi_node #0 - the aggregator that carries GPS and
                // the wired link to the CYD (see docs/SCALING.md).
                val nodeBits = mutableListOf<String>()
                health.wifiNodeUp?.let { nodeBits.add(if (it) "MAIN NODE UP" else "MAIN NODE DOWN") }
                health.freeHeapKB?.let { nodeBits.add("RAM ${it}K") }
                val telemetry = if (nodeBits.isNotEmpty()) "\n${nodeBits.joinToString(" · ")}" else ""
                binding.rigHealthText.text = "$gps · $sd$sdFree$pend$telemetry"
                val nodeDown = health.wifiNodeUp == false
                binding.rigHealthText.setTextColor(ContextCompat.getColor(this,
                    if (health.gpsFix && health.sdOk && !nodeDown) R.color.cyan_500 else R.color.red_error))
            }

            val shown = wifiShown + bleShown + cell
            val excluded = service.excludedCount
            val newFinds = service.newThisRun
            val newSuffix = if (newFinds > 0) "  ·  NEW $newFinds" else ""
            binding.totalStatus.text = if (excluded > 0) {
                "TOTAL: ${shown + excluded}  (${excluded} EXCLUDED)$newSuffix"
            } else {
                "TOTAL: $shown$newSuffix"
            }

            updateMapStatusOverlay(service, wifiShown, wifiShown + bleShown, bleShown)
        }

        // Live speed/heading/distance - the same totalDistanceMeters Session
        // Stats already reads, just surfaced without a tap so it's visible
        // at a glance while actually driving, not just after the fact. The
        // banner (speedValueText/distanceValueText) always shows something,
        // matching the reference layout's always-on bar; sessionStatus in
        // the telemetry ticker only appears mid-run, same as before.
        if (service != null && service.running) {
            val distanceMi = service.totalDistanceMeters / 1609.34
            if (service.paused) {
                binding.sessionStatus.text = "PAUSED  ·  %.2f MI".format(distanceMi)
                binding.speedHeadingText.text = "-"
                binding.speedValueText.text = "PAUSED"
                binding.distanceValueText.text = "%.1f mi".format(distanceMi)
            } else {
                val loc = service.locationTracker.lastLocation
                val speedMph = if (loc != null && loc.hasSpeed() && loc.speed > 0.5f) loc.speed * 2.23694f else 0f
                val speedText = if (speedMph > 0f) "%.0f MPH".format(speedMph) else "STOPPED"
                val headingText = if (loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) {
                    " " + headingLabel(loc.bearing)
                } else ""
                binding.sessionStatus.text = "%s%s  ·  %.2f MI".format(speedText, headingText, distanceMi)
                binding.speedHeadingText.text = if (headingText.isNotEmpty()) headingText.trim() else "-"
                binding.speedValueText.text = "%.0f mph".format(speedMph)
                binding.distanceValueText.text = "%.1f mi".format(distanceMi)
            }
            binding.sessionStatus.visibility = View.VISIBLE
        } else {
            binding.sessionStatus.visibility = View.GONE
            binding.speedHeadingText.text = "-"
            binding.speedValueText.text = "0 mph"
            binding.distanceValueText.text = "0.0 mi"
        }
    
        ThemeManager.apply(binding.root, this)
    }

    // Always-visible corner readout on the map screen (as opposed to the Dashboard tab's own
    // telemetry row, which you have to switch tabs to see) - shows ON/OFF/PAUSED plus the
    // WIGLE/WDGW/BT counts and both link states (phone-to-CYD "CYD", shown as BLE/USB, and CYD-to-wifi_node "Rig")
    // at a glance while actually looking at the map, which is what you're doing most of a drive.
    // Deliberately never hidden, including while stopped - the point is a glance answers "is
    // everything connected and running" without needing to already be running first (2026-09-28).
    private fun updateMapStatusOverlay(service: ScanService?, wigle: Int, wdgw: Int, bt: Int) {
        val running = service?.running == true
        val paused = service?.paused == true
        val rigConnected = service?.rigConnected == true
        val meshState = service?.meshLinkState ?: RigLinkManager.MeshLinkState.DISCONNECTED

        val builder = SpannableStringBuilder()
        fun appendColored(text: String, color: Int) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, 0)
        }
        fun colorOf(resId: Int) = ContextCompat.getColor(this, resId)

        appendColored(
            when {
                paused -> "PAUSED"
                running -> "ON"
                else -> "OFF"
            },
            colorOf(if (running && !paused) R.color.green_ok else R.color.red_error),
        )
        builder.append("  ·  ")
        if (appSettings.rigCountFoundMode) { // same order/colors as the CYD's found-device view
            appendColored("APS $wigle", colorOf(R.color.cyan_500))
            builder.append("  ·  ")
            appendColored("BT $bt", colorOf(R.color.green_ok))
            builder.append("  ·  ")
            appendColored("ALL $wdgw", colorOf(R.color.purple_500))
        } else {
            appendColored("WIGLE $wigle", colorOf(R.color.cyan_500))
            builder.append("  ·  ")
            appendColored("WDGW $wdgw", colorOf(R.color.purple_500))
            builder.append("  ·  ")
            appendColored("BT $bt", colorOf(R.color.green_ok))
        }
        builder.append("  ·  ")
        appendColored(
            if (rigConnected) "CYD ${service?.rigLink?.connectionType ?: "✓"}" else "CYD ✗",
            colorOf(if (rigConnected) R.color.green_ok else R.color.red_error),
        )
        builder.append("  ·  ")
        appendColored(
            when (meshState) {
                RigLinkManager.MeshLinkState.CONNECTED -> "Rig ✓"
                RigLinkManager.MeshLinkState.CONNECTING -> "Rig …"
                RigLinkManager.MeshLinkState.DISCONNECTED -> "Rig ✗"
            },
            colorOf(
                when (meshState) {
                    RigLinkManager.MeshLinkState.CONNECTED -> R.color.green_ok
                    RigLinkManager.MeshLinkState.CONNECTING -> R.color.purple_500
                    RigLinkManager.MeshLinkState.DISCONNECTED -> R.color.red_error
                }
            ),
        )

        binding.mapStatusOverlay.text = builder
    }

    private fun headingLabel(bearing: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val norm = ((bearing % 360) + 360) % 360
        val idx = ((norm / 45.0 + 0.5).toInt()) % 8
        return dirs[idx]
    }

    private fun exportCsv() {
        Thread {
            // Nothing currently running - fall back to the most recently
            // saved run so "export" still works after a stop.
            val runId = scanService?.currentRunId() ?: dao.mostRecentRunId() ?: return@Thread
            val file = CsvExporter.materialize(this, dao, runId) ?: return@Thread
            runOnUiThread { shareFile(file) }
        }.start()
    }

    private fun shareRun(run: RunSummary) {
        Thread {
            val file = CsvExporter.materialize(this, dao, run.id)
            runOnUiThread {
                if (file == null) Toast.makeText(this, "Couldn't export that run", Toast.LENGTH_SHORT).show()
                else shareFile(file)
            }
        }.start()
    }

    private fun shareFile(file: File, mimeType: String = "text/csv") {
        val uri: Uri = FileProvider.getUriForFile(this, "com.dreknil.wardrivebridge.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Wardrive file"))
    }

    // ---- Logs tab ----

    private fun refreshLogsView() {
        if (browsingAllLogs) refreshHistoricalBrowse() else refreshLogsList()
    }

    private fun refreshLogsList() {
        binding.logsList.adapter = logsAdapter
        val query = logsSearchQuery.trim()
        Thread {
            val runs = dao.allRunSummaries()
                .filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.note.contains(query, ignoreCase = true) }
            runOnUiThread {
                logsAdapter.submitList(runs)
                logsAdapter.setSelectionState(selectionMode, selectedRunIds)
                binding.logsEmptyText.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
                binding.logsList.visibility = if (runs.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    // Same file-tree grouping the live feed uses, but by WiFi/BLE/Cell type
    // only - which radio (rig vs phone) saw a device isn't preserved in the
    // saved CSV format, so a 5-way split like the live feed's isn't
    // reconstructible here.
    private fun toggleBrowseMode() {
        browsingAllLogs = !browsingAllLogs
        binding.browseAllButton.text = if (browsingAllLogs) "> SHOW FILES" else "> BROWSE ALL"
        refreshLogsView()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        selectedRunIds.clear()
        binding.selectModeButton.text = if (selectionMode) "> CANCEL" else "> SELECT"
        binding.deleteSelectedButton.visibility = View.GONE
        refreshLogsList()
    }

    private fun toggleRunSelection(runId: Long) {
        if (!selectedRunIds.remove(runId)) selectedRunIds.add(runId)
        binding.deleteSelectedButton.text = "> DELETE SELECTED (${selectedRunIds.size})"
        binding.deleteSelectedButton.visibility = if (selectedRunIds.isEmpty()) View.GONE else View.VISIBLE
        logsAdapter.setSelectionState(selectionMode, selectedRunIds)
    }

    private fun confirmDeleteSelected() {
        val count = selectedRunIds.size
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle("Delete $count log${if (count == 1) "" else "s"}?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ids = selectedRunIds.toList()
                Thread {
                    for (id in ids) dao.deleteRunWithObservations(id)
                    runOnUiThread {
                        selectionMode = false
                        selectedRunIds.clear()
                        binding.selectModeButton.text = "> SELECT"
                        binding.deleteSelectedButton.visibility = View.GONE
                        refreshLogsList()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFloorPlanImage(uri: Uri) {
        val file = FloorPlanStore.importImage(this, uri)
        if (file == null) {
            Toast.makeText(this, "Couldn't import that image", Toast.LENGTH_SHORT).show()
            return
        }
        openFloorPlan(file)
    }

    private fun openFloorPlan(file: File) {
        // Decode off the main thread - a phone-camera floor-plan photo can be
        // several MB and would visibly hitch the UI if decoded inline.
        Thread {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            val markers = if (bmp != null) FloorPlanStore.loadMarkers(file) else mutableListOf()
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, "Couldn't decode that image", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                currentFloorPlanImage = file
                binding.floorPlanView.setImage(bmp)
                floorPlanMarkers = markers
                binding.floorPlanView.setMarkers(floorPlanMarkers)
                binding.floorPlanHint.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun loadMostRecentFloorPlan() {
        FloorPlanStore.listFloorPlans(this).firstOrNull()?.let { openFloorPlan(it) }
    }

    private fun pickFloorPlan() {
        val plans = FloorPlanStore.listFloorPlans(this)
        if (plans.isEmpty()) {
            Toast.makeText(this, "No saved floor plans yet - import one first", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = plans.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Switch Floor Plan")
            .setItems(labels) { _, which -> openFloorPlan(plans[which]) }
            .show()
    }

    private fun deleteCurrentFloorPlan() {
        val file = currentFloorPlanImage ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this floor plan?")
            .setMessage("This removes the image and all its points. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                FloorPlanStore.delete(file)
                currentFloorPlanImage = null
                floorPlanMarkers = mutableListOf()
                binding.floorPlanView.setImage(null)
                binding.floorPlanView.setMarkers(emptyList())
                binding.floorPlanHint.visibility = View.GONE
                loadMostRecentFloorPlan()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onFloorPlanTap(xFrac: Float, yFrac: Float, hitIndex: Int?) {
        val file = currentFloorPlanImage
        if (file == null) {
            Toast.makeText(this, "Import a floor plan first", Toast.LENGTH_SHORT).show()
            return
        }
        if (hitIndex != null) {
            showFloorPlanMarkerOptions(file, hitIndex)
            return
        }
        val input = EditText(this).apply { hint = "Label (optional)" }
        AlertDialog.Builder(this)
            .setTitle("Add Point")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                floorPlanMarkers.add(FloorPlanMarker(xFrac, yFrac, input.text.toString().trim(), FloorPlanStore.nowIso()))
                FloorPlanStore.saveMarkers(file, floorPlanMarkers)
                binding.floorPlanView.setMarkers(floorPlanMarkers)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFloorPlanMarkerOptions(file: File, index: Int) {
        val marker = floorPlanMarkers.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(marker.label.ifBlank { "Point" })
            .setItems(arrayOf("Rename", "Remove")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(marker.label) }
                        AlertDialog.Builder(this)
                            .setTitle("Rename Point")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                floorPlanMarkers[index] = marker.copy(label = input.text.toString().trim())
                                FloorPlanStore.saveMarkers(file, floorPlanMarkers)
                                binding.floorPlanView.setMarkers(floorPlanMarkers)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        floorPlanMarkers.removeAt(index)
                        FloorPlanStore.saveMarkers(file, floorPlanMarkers)
                        binding.floorPlanView.setMarkers(floorPlanMarkers)
                    }
                }
            }
            .show()
    }

    private fun toggleHistGroup(label: String) {
        expandedHistGroups[label] = !(expandedHistGroups[label] ?: false)
        refreshHistoricalBrowse()
    }

    private fun refreshHistoricalBrowse() {
        binding.logsList.adapter = historicalAdapter
        Thread {
            val points = dao.latestPerMac().map { it.toHistoricalPoint() }
            val satellites = dao.allGnssSatellites()
            runOnUiThread {
                binding.logsEmptyText.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
                binding.logsList.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
                historicalAdapter.submitList(buildHistoricalGroupedFeed(points, satellites))
            }
        }.start()
    }

    // Field Report categories - the first three ("WiFi"/"Bluetooth"/"Cell")
    // are mutually exclusive (by radio type); "Cell Towers" is the same Cell
    // rows again, just sub-grouped by technology (LTE/NR/GSM/etc) instead of
    // shown flat; the rest overlap the first three (a BLE device can also be
    // flagged as a tracker/Flipper/Flock/skimmer/drone/etc) - matching how
    // the reference Wardrive Go app lists "by radio" and "by device kind"
    // side by side, not as a strict partition. Every category here is real,
    // backed by an actual signature in DeviceSignatureDetection/
    // TrackerDetection - "ALPR cams" and "RF/attack" and "KARR immobilizer"
    // from that reference app still aren't shown, since there's no
    // confident public signature for them and a permanent "0" would be
    // misleading rather than informative, same reasoning as before
    // (2026-09-27) just narrowed to the categories that still apply.
    //
    // "By Maker" and "GNSS Satellites" are a different shape - each top
    // bucket expands into per-vendor/per-constellation SUB-headers rather
    // than flat rows, reusing the exact same Header/Row + onHeaderClick
    // mechanism recursively (a sub-header's groupLabel is just namespaced,
    // e.g. "By Maker: Apple", so it gets its own independent expand state in
    // expandedHistGroups without colliding with the top-level "By Maker" key
    // or another top-level category that happens to share a vendor name).
    private fun buildHistoricalGroupedFeed(
        points: List<HistoricalPoint>,
        satellites: List<GnssSatelliteEntity> = emptyList(),
    ): List<HistoricalFeedItem> {
        val query = logsSearchQuery.trim()
        val filteredPoints = if (query.isEmpty()) points else points.filter { matchesQuery(it.label, it.mac, query) }
        val buckets = linkedMapOf(
            "WiFi" to mutableListOf<HistoricalPoint>(),
            "Bluetooth" to mutableListOf(),
            "Cell" to mutableListOf(),
            "Trackers" to mutableListOf(),
            "Flipper Zero" to mutableListOf(),
            "Flock Cameras" to mutableListOf(),
            "Skimmers" to mutableListOf(),
            "Drones" to mutableListOf(),
            "Mesh Radios" to mutableListOf(),
            "Glasses" to mutableListOf(),
            "Action Cams" to mutableListOf(),
            "Police Cams" to mutableListOf(),
            "Pineapples" to mutableListOf(),
        )
        for (p in filteredPoints) {
            when {
                p.type.equals("WIFI", ignoreCase = true) -> buckets.getValue("WiFi").add(p)
                p.type.equals("BLE", ignoreCase = true) -> buckets.getValue("Bluetooth").add(p)
                else -> buckets.getValue("Cell").add(p)
            }
            if (p.isTracker) buckets.getValue("Trackers").add(p)
            if (p.isFlipperZero) buckets.getValue("Flipper Zero").add(p)
            if (p.isFlockCamera) buckets.getValue("Flock Cameras").add(p)
            if (p.isSkimmer) buckets.getValue("Skimmers").add(p)
            if (p.isDrone) buckets.getValue("Drones").add(p)
            if (p.isMeshRadio) buckets.getValue("Mesh Radios").add(p)
            if (p.isGlasses) buckets.getValue("Glasses").add(p)
            if (p.isActionCam) buckets.getValue("Action Cams").add(p)
            if (p.isPoliceCam) buckets.getValue("Police Cams").add(p)
            if (p.isPineapple) buckets.getValue("Pineapples").add(p)
        }

        val out = mutableListOf<HistoricalFeedItem>()
        for ((label, list) in buckets) {
            if (list.isEmpty() && query.isNotEmpty()) continue // hide empty categories only while actively searching
            val expanded = if (query.isNotEmpty()) list.isNotEmpty() else (expandedHistGroups[label] ?: false)
            out.add(HistoricalFeedItem.Header(label, list.size, expanded))
            if (expanded) out.addAll(list.map { HistoricalFeedItem.Row(it) })
        }

        // Cell Towers: the same Cell rows, sub-grouped by technology - a
        // second view of data already shown flat under "Cell" above, not a
        // new data source (PhoneCellScanner already queries every visible
        // tower via TelephonyManager.allCellInfo, not just the serving one -
        // "Cell" and "Cell Towers" were always the same underlying reach,
        // this just presents it the way the reference app's own screenshot
        // does).
        val cellPoints = buckets.getValue("Cell")
        if (cellPoints.isNotEmpty() || query.isEmpty()) {
            val expanded = expandedHistGroups["Cell Towers"] ?: false
            out.add(HistoricalFeedItem.Header("Cell Towers", cellPoints.size, expanded))
            if (expanded) {
                val byTech = cellPoints.groupBy { it.authType.ifBlank { "Unknown" } }
                for ((tech, list) in byTech) {
                    val subLabel = "Cell Towers: $tech"
                    val subExpanded = expandedHistGroups[subLabel] ?: false
                    out.add(HistoricalFeedItem.Header(subLabel, list.size, subExpanded))
                    if (subExpanded) out.addAll(list.map { HistoricalFeedItem.Row(it) })
                }
            }
        }

        // By Maker: every point (any radio/category) regrouped by resolved
        // OUI vendor instead of by source/category - randomized/unknown MACs
        // (OuiLookup.vendorFor() returns null) are dropped from this view
        // entirely rather than dumped in a meaningless "Unknown" bucket that
        // would dwarf every real vendor.
        if (points.isNotEmpty() || query.isEmpty()) {
            val byMakerAll = points.mapNotNull { p -> OuiLookup.vendorFor(p.mac)?.let { it to p } }
            val filtered = if (query.isEmpty()) byMakerAll else byMakerAll.filter { (_, p) -> matchesQuery(p.label, p.mac, query) }
            if (filtered.isNotEmpty() || query.isEmpty()) {
                val expanded = expandedHistGroups["By Maker"] ?: false
                out.add(HistoricalFeedItem.Header("By Maker", filtered.size, expanded))
                if (expanded) {
                    val byVendor = filtered.groupBy({ it.first }, { it.second })
                    for ((vendor, list) in byVendor.toList().sortedByDescending { it.second.size }) {
                        val subLabel = "By Maker: $vendor"
                        val subExpanded = expandedHistGroups[subLabel] ?: false
                        out.add(HistoricalFeedItem.Header(subLabel, list.size, subExpanded))
                        if (subExpanded) out.addAll(list.map { HistoricalFeedItem.Row(it) })
                    }
                }
            }
        }

        // GNSS Satellites: app-only, never uploaded - every satellite this
        // phone's own GPS chip has ever reported (see GnssSatelliteEntity),
        // sub-grouped by constellation. Not filtered by the MAC/name search
        // box (a satellite has neither), so it's skipped entirely while a
        // search query is active rather than shown as a confusing "0 results
        // still visible" bucket.
        if (satellites.isNotEmpty() && query.isEmpty()) {
            val expanded = expandedHistGroups["GNSS Satellites"] ?: false
            out.add(HistoricalFeedItem.Header("GNSS Satellites", satellites.size, expanded))
            if (expanded) {
                val byConstellation = satellites.groupBy { it.constellation }
                for ((constellation, list) in byConstellation) {
                    val subLabel = "GNSS Satellites: $constellation"
                    val subExpanded = expandedHistGroups[subLabel] ?: false
                    out.add(HistoricalFeedItem.Header(subLabel, list.size, subExpanded))
                    if (subExpanded) out.addAll(list.map { HistoricalFeedItem.Row(it.toHistoricalPoint()) })
                }
            }
        }

        return out
    }

    private fun GnssSatelliteEntity.toHistoricalPoint() = HistoricalPoint(
        mac = "$constellation-$svid",
        label = "$constellation PRN $svid",
        authType = constellation,
        firstSeen = lastSeenIso,
        channel = 0, frequency = 0,
        rssi = lastCn0DbHz.toInt(),
        lat = 0.0, lon = 0.0, altitude = 0.0, accuracy = 0.0,
        type = "GNSS",
    )

    private fun showRunOptions(run: RunSummary) {
        AlertDialog.Builder(this)
            .setTitle(run.note.ifBlank { run.label })
            .setItems(arrayOf("View on Map", "Share", "Add/Edit Note", "Export as GPX", "Export as KML (Google Earth)", "Export as Aircrack CSV", "Delete")) { _, which ->
                when (which) {
                    0 -> viewRunOnMap(run)
                    1 -> shareRun(run)
                    2 -> editNote(run)
                    3 -> exportGpx(run)
                    4 -> exportKml(run)
                    5 -> exportAircrack(run)
                    6 -> confirmDeleteLog(run)
                }
            }
            .show()
    }

    private fun editNote(run: RunSummary) {
        val input = android.widget.EditText(this)
        input.setText(run.note)
        input.hint = "e.g. \"downtown loop\""
        AlertDialog.Builder(this)
            .setTitle("Note for ${run.label}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newNote = input.text.toString().trim()
                Thread {
                    dao.setNote(run.id, newNote)
                    runOnUiThread { refreshLogsList() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportGpx(run: RunSummary) {
        Thread {
            val points = dao.observationsForRun(run.id).map { it.toHistoricalPoint() }
            val dir = File(getExternalFilesDir(null), "wardrive")
            if (!dir.exists()) dir.mkdirs()
            val gpxFile = GpxExporter.convert(points, File(dir, "${run.label}.gpx"))
            runOnUiThread {
                if (gpxFile == null) {
                    Toast.makeText(this, "No located points to export", Toast.LENGTH_SHORT).show()
                } else {
                    shareFile(gpxFile, "application/gpx+xml")
                }
            }
        }.start()
    }

    private fun exportAircrack(run: RunSummary) {
        Thread {
            val file = AircrackExporter.export(this, dao, run.id)
            runOnUiThread {
                if (file == null) Toast.makeText(this, "Nothing to export in that log", Toast.LENGTH_SHORT).show()
                else shareFile(file, "text/csv")
            }
        }.start()
    }

    private fun viewRunOnMap(run: RunSummary) {
        Thread {
            val points = dao.observationsForRun(run.id).map { it.toHistoricalPoint() }
            runOnUiThread {
                if (points.isEmpty()) {
                    Toast.makeText(this, "No located points in that log", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                mapManager.showHistorical(points)
                showDetail(DetailKind.MAP, "Wardriving")
                Toast.makeText(this, "Showing ${points.size} points from ${run.label}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun viewAllLogsOnMap() {
        Thread {
            val points = dao.latestPerMac().map { it.toHistoricalPoint() }
            runOnUiThread {
                if (points.isEmpty()) {
                    Toast.makeText(this, "No located points in any log yet", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                mapManager.showHistorical(points)
                showDetail(DetailKind.MAP, "Wardriving")
                Toast.makeText(this, "Showing ${points.size} points from every log", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun confirmDeleteLog(run: RunSummary) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${run.note.ifBlank { run.label }}?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    dao.deleteRunWithObservations(run.id)
                    runOnUiThread { refreshLogsList() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadCsv() {
        // currentRunId() is a cheap in-memory field, safe on the main thread - but the
        // dao.mostRecentRunId() fallback (used once there's no active run, e.g. it already
        // stopped) hits the database and must not run here. This used to call it directly on
        // the calling thread and crash with "Cannot access database on the main thread" every
        // time Upload was tapped with no run active - confirmed via a real crash report
        // (2026-09-28) after a run stopped mid-drive from a dropped rig connection.
        val liveRunId = scanService?.currentRunId()
        if (liveRunId != null) {
            uploadRun(liveRunId)
            return
        }
        Thread {
            val runId = dao.mostRecentRunId()
            if (runId == null) {
                runOnUiThread { Toast.makeText(this, "No CSV to upload yet", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            runOnUiThread { uploadRun(runId) }
        }.start()
    }

    // Reused by both the Dashboard's "Upload" quick action (most recent/
    // current run) and the Logs tab's per-row Upload button (any past run,
    // found by searching for exactly the ones missing this - see
    // LogsAdapter's uploadedAt-gated button). A success here is the ONLY
    // thing that marks a run uploaded; a failure leaves it showing as missed
    // so it stays easy to find and retry.
    private fun exportKml(run: RunSummary) {
        Thread {
            val points = dao.observationsForRun(run.id).map { it.toHistoricalPoint() }
            val dir = File(getExternalFilesDir(null), "wardrive")
            if (!dir.exists()) dir.mkdirs()
            val kml = KmlExporter.convert(points, run.note.ifBlank { run.label }, File(dir, "${run.label}.kml"))
            runOnUiThread {
                if (kml == null) Toast.makeText(this, "No located points to export", Toast.LENGTH_SHORT).show()
                else shareFile(kml, "application/vnd.google-earth.kml+xml")
            }
        }.start()
    }

    /** Every saved run as its own WigleWifi CSV, in one zip - a full backup, or a bulk import
     *  into WiGLE's web uploader. */
    private fun exportAllRuns() {
        Toast.makeText(this, "Building export…", Toast.LENGTH_SHORT).show()
        Thread {
            val runs = dao.allRunSummaries().filter { it.count > 0 }
            val dir = File(getExternalFilesDir(null), "wardrive")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
            val zip = File(dir, "wardrive_all_runs_$stamp.zip")
            var added = 0
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                for (run in runs) {
                    val csv = CsvExporter.materialize(this, dao, run.id) ?: continue
                    zos.putNextEntry(java.util.zip.ZipEntry(csv.name))
                    csv.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    added++
                }
            }
            runOnUiThread {
                if (added == 0) {
                    zip.delete()
                    Toast.makeText(this, "No runs to export yet", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "$added runs exported", Toast.LENGTH_SHORT).show()
                    shareFile(zip, "application/zip")
                }
            }
        }.start()
    }

    private fun confirmRigUpload() {
        val health = scanService?.rigHealth ?: return
        AlertDialog.Builder(this)
            .setTitle("Upload rig data now?")
            .setMessage(
                (if (health.pendingUploads > 0) "The rig has ${health.pendingUploads} run(s) waiting. " else "Nothing is waiting on the rig right now. ") +
                    "It will stop scanning, join its home WiFi from config.cfg, upload, then carry on. " +
                    "The Bluetooth link drops for the upload and reconnects by itself.",
            )
            .setPositiveButton("UPLOAD") { _, _ ->
                val sent = scanService?.requestRigUpload() == true
                Toast.makeText(this, if (sent) "Rig upload started" else "Rig not connected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportMenu() {
        AlertDialog.Builder(this)
            .setTitle("Export")
            .setItems(arrayOf("This run (CSV)", "All runs (zip of CSVs)")) { _, which ->
                if (which == 0) exportCsv() else exportAllRuns()
            }
            .show()
    }

    private fun uploadRun(run: RunSummary) = uploadRun(run.id)

    private fun uploadRun(runId: Long) {
        val wigleToken = appSettings.wigleToken
        val wdgwarsKey = appSettings.wdgwarsKey
        if (wigleToken.isBlank() && wdgwarsKey.isBlank()) {
            Toast.makeText(this, "Add a WiGLE token or wdgwars key in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show()
        Thread {
            val file = CsvExporter.materialize(this, dao, runId)
            if (file == null) {
                runOnUiThread { Toast.makeText(this, "No CSV to upload yet", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            uploadManager.upload(file, wigleToken, wdgwarsKey) { result ->
                // Only counts as fully uploaded (hides the retry button) once every
                // target the user actually has configured succeeded - previously this
                // used OR, so with both a WiGLE token and a wdgwars key set, one
                // target succeeding marked the whole run SENT even if the other
                // failed, hiding the button with no way to retry the failed one.
                val allOk = (!result.wdgwarsAttempted || result.wdgwarsOk) && (!result.wigleAttempted || result.wigleOk)
                if (allOk) dao.markUploaded(runId, System.currentTimeMillis())
                runOnUiThread {
                    val parts = mutableListOf<String>()
                    if (result.wdgwarsAttempted) parts.add("wdgwars: ${if (result.wdgwarsOk) "ok" + (uploadManager.summarizeWdgwars(result.wdgwarsMessage)?.let { " ($it)" } ?: "") else result.wdgwarsMessage}")
                    if (result.wigleAttempted) parts.add("WiGLE: ${if (result.wigleOk) "ok" else result.wigleMessage}")
                    Toast.makeText(this, parts.joinToString("  ·  "), Toast.LENGTH_LONG).show()
                    if (result.wdgwarsOk || result.wigleOk) {
                        refreshAccountStats()
                        refreshLogsView()
                    }
                }
            }
        }.start()
    }

    companion object {
        private const val BATTERY_CHECK_INTERVAL_MS = 5000L
    }
}
