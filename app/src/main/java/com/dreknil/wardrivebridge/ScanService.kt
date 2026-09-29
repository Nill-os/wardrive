package com.dreknil.wardrivebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Owns every radio/GPS/rig connection and the current run's session state
 * (dedup groups, CSV writer, privacy filtering) as a foreground service, so
 * a locked screen or a backgrounded app doesn't silently throttle or kill
 * mid-drive data collection - the exact failure mode this project ran into
 * before this existed. MainActivity binds to it for live UI updates but
 * this service's own lifecycle - and therefore the actual scanning and
 * logging - does not depend on any Activity being alive.
 *
 * Promotes itself to a real foreground service (persistent notification)
 * only while a run is active (startRun()/stopRun()) - that's the only time
 * there's anything to lose from being throttled. Outside of a run it stays
 * a plain started service just holding the rig's USB connection open,
 * which isn't subject to the same background radio/location restrictions.
 */
class ScanService : Service(), RigLinkManager.Listener {

    inner class LocalBinder : Binder() {
        val service: ScanService get() = this@ScanService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    /** All callbacks land on the main thread - mirrors the discipline the
     * old Activity-owned version relied on (every scanner callback was
     * wrapped in runOnUiThread), now enforced here instead so `groups` and
     * `csvWriter` are never touched from two threads at once. */
    interface SessionListener {
        fun onRigConnected()
        fun onRigDisconnected()
        /** cyd_node's own mesh link to wifi_node - see RigLinkManager.Listener's own doc for why
         * this is distinct from onRigConnected/Disconnected (the phone's USB link to cyd_node). */
        fun onMeshLinkStateChanged(state: RigLinkManager.MeshLinkState)
        fun onRunStateChanged(running: Boolean)
        /** Scanning temporarily suspended without ending the run - see
         * pauseRun()/resumeRun(). Only ever fires while running is true. */
        fun onPauseStateChanged(paused: Boolean)
        fun onObservation(tagged: Observation)
        fun onLocationChanged(location: android.location.Location)
        /** A flagged AirTag/SmartTag was also seen in a past run, far
         * enough from where it was seen then that it looks like it's
         * actually traveling with you rather than just a stationary
         * tracker near a place you both happened to pass. Fired at most
         * once per MAC per run (see alertedTrackers). */
        fun onPersistentTrackerAlert(tagged: Observation, previousSighting: HistoricalPoint)
        /** Raw rig USB traffic (RX lines from the rig, TX for commands this
         * app sends) - purely for the Terminal tab's live log, no other
         * behavior depends on it. */
        fun onRigLogLine(line: String)
    }
    var listener: SessionListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var rigLink: RigLinkManager; private set
    lateinit var locationTracker: LocationTracker; private set
    private lateinit var wifiScanner: PhoneWifiScanner
    private lateinit var bleScanner: PhoneBleScanner
    private lateinit var cellScanner: PhoneCellScanner
    private lateinit var appSettings: AppSettings
    private lateinit var dao: WardriveDao

    // Room forbids DB access on the main thread (a deliberate, correct
    // restriction this app didn't have before - the old CSV writer did
    // synchronous file I/O directly on the main thread per observation).
    // A single background thread keeps writes strictly in insertion order
    // without needing a full coroutine/Flow rewrite of this Thread+Handler-
    // based codebase - same "fire and forget, swallow I/O errors" risk
    // tolerance the old WigleCsvWriter.append() had.
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private var currentRunId: Long? = null

    var running = false; private set
    // True while a run is active but scanning is temporarily suspended -
    // distinct from `running`, which stays true the whole time (pausing
    // doesn't end or finalize the run the way stopRun() does). Only
    // meaningful when running is true; startRun()/stopRun() always clear it.
    var paused = false; private set
    var rigConnected = false; private set
    var meshLinkState = RigLinkManager.MeshLinkState.DISCONNECTED; private set
    // Parsed out of the rig's own periodic "WD:STATUS ... ch=N ..." line (see
    // onRigStatus()) - the phone's own WiFi scan has no single "active
    // channel" the way the rig's channel-hopping sniffer does, so this is
    // rig-only telemetry, 0 until the first status line ever arrives.
    var lastRigChannel = 0; private set

    /** The rig's own health, from the extra fields in its WD:STATUS line (newer firmware). */
    data class RigHealth(val gpsFix: Boolean, val sats: Int, val sdOk: Boolean, val pendingUploads: Int, val atMs: Long)
    var rigHealth: RigHealth? = null; private set
    var runStartMs = 0L; private set
    var totalDistanceMeters = 0.0; private set
    private var lastFixForDistance: android.location.Location? = null
    private var lastGnssPersistMs = 0L
    private val gnssIsoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // How many observations isFiltered() dropped this run (_nomap/_optout,
    // blacklist, home exclusion zone) - these never reach `groups` at all,
    // so without a separate counter "total found" would silently undercount
    // by however many got excluded, with no way to tell the difference
    // between "found less" and "found the same but some got filtered out".
    var excludedCount = 0; private set

    // Snapshot of every device ever logged in a *previous* run, taken once
    // at startRun() (before this run's own CSV exists) - see
    // SessionListener.onPersistentTrackerAlert and Observation.isReturning.
    // @Volatile since it's written once from the background-load thread and
    // read from the main thread on every observation - a simple
    // publish-once reference swap, but needs the visibility guarantee.
    @Volatile private var historicalIndex: Map<String, HistoricalPoint> = emptyMap()
    private val alertedTrackers = mutableSetOf<String>()

    // Per-run dedup, one map per source, keyed by MAC (or the synthetic cell
    // identity string) - see MainActivity's old comment history for why:
    // a repeat sighting updates in place rather than piling up duplicates.
    val groups: Map<Source, LinkedHashMap<String, Observation>> =
        Source.entries.associateWith { LinkedHashMap() }

    // The rig and the phone each run their own WiFi/BLE radio, so the same
    // real-world AP or BLE device can get detected independently by both -
    // groups above is keyed per-Source, so it has no way to know
    // Source.RIG_WIFI and Source.PHONE_WIFI just logged the same MAC a few
    // seconds apart at two nearby points along the drive. Confirmed for
    // real against a live database: one drive logged the same BSSID twice,
    // ~65m and 7s apart, once from each radio. These two sets are the
    // cross-source gate specifically for the DB/CSV/upload write - keyed by
    // radio kind (not Source), reset per run, so a MAC only ever gets one
    // row no matter which radio(s) saw it. The per-Source `groups` maps
    // above are deliberately left alone - "rig found 30, phone found 12" is
    // still a real, useful live-view distinction, just not one that should
    // multiply the number of rows written anywhere data leaves the app.
    private val loggedWifiMacsThisRun = mutableSetOf<String>()
    private val loggedBleMacsThisRun = mutableSetOf<String>()
    private val loggedCellIdsThisRun = mutableSetOf<String>()

    private val spoken by lazy { SpokenUpdates(this, appSettings) { wifiCountThisRun to bleCountThisRun } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appSettings = AppSettings(this)
        dao = AppDatabase.get(this).dao()
        rigLink = RigLinkManager(applicationContext, this)
        locationTracker = LocationTracker(applicationContext)
        locationTracker.onNewFix = { loc ->
            lastFixForDistance?.let { prev -> if (running) totalDistanceMeters += prev.distanceTo(loc) }
            lastFixForDistance = loc
            listener?.onLocationChanged(loc)
        }
        // Persisted for the Field Report's "GNSS Satellites" section (app-only,
        // never uploaded - see GnssSatelliteEntity's own comment). GnssStatus
        // fires roughly once a second per constellation update; throttled to
        // once every GNSS_PERSIST_INTERVAL_MS so a multi-hour drive doesn't
        // turn this into a write on every single tick for no real benefit -
        // the Field Report only needs "seen at some point," not a live feed.
        locationTracker.onSatelliteStatus = { readings ->
            val now = System.currentTimeMillis()
            if (now - lastGnssPersistMs >= GNSS_PERSIST_INTERVAL_MS && readings.isNotEmpty()) {
                lastGnssPersistMs = now
                val nowIso = gnssIsoFormat.format(Date())
                val entities = readings.map {
                    GnssSatelliteEntity(
                        constellation = it.constellation, svid = it.svid,
                        lastCn0DbHz = it.cn0DbHz, usedInFix = it.usedInFix, lastSeenIso = nowIso,
                    )
                }
                dbExecutor.execute { dao.upsertGnssSatellites(entities) }
            }
        }
        wifiScanner = PhoneWifiScanner(applicationContext) { obs -> mainHandler.post { onObservation(obs) } }
        bleScanner = PhoneBleScanner(applicationContext) { obs -> mainHandler.post { onObservation(obs) } }
        cellScanner = PhoneCellScanner(applicationContext) { obs -> mainHandler.post { onObservation(obs) } }
        createNotificationChannel()
        rigLink.start() // registers the USB permission receiver once and attempts an initial connect
        cleanupOldLogs()
    }

    // Mirrors the firmware's own retentionDays/cleanupOldFiles() convention
    // on the phone side - runs once per service start (roughly once per app
    // launch), off the main thread since it touches the DB. 0 = keep
    // forever, same as the firmware default.
    private fun cleanupOldLogs() {
        // Runs with nothing in them - normally deleted when the run stops, but a crash or a
        // force-stop mid-run skips that and leaves an empty entry in the logs forever.
        // Nothing is running yet at this point, so every empty run is an orphan.
        dbExecutor.execute {
            dao.allRunSummaries().filter { it.count == 0 }.forEach { dao.deleteRunWithObservations(it.id) }
        }
        val days = appSettings.retentionDays
        if (days <= 0) return
        val cutoffMs = System.currentTimeMillis() - days * 24L * 3600L * 1000L
        dbExecutor.execute { dao.deleteRunsOlderThan(cutoffMs) }
    }

    // The notification's STOP button reaches the service through this action.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (running) stopRun(notifyRig = true)
        }
        return START_STICKY
    }

    /** Tells the rig to upload its SD-card runs now. False if no rig is connected. */
    fun requestRigUpload(): Boolean {
        if (!rigConnected) return false
        rigLink.sendRigUpload()
        return true
    }

    /** Distinct WiFi / BLE devices logged this run - for the notification, widget and speech. */
    val wifiCountThisRun: Int get() = loggedWifiMacsThisRun.size
    val bleCountThisRun: Int get() = loggedBleMacsThisRun.size

    private var lastExternalUiUpdateMs = 0L

    /** Refreshes everything outside the app that shows run state: notification, widget, tile. */
    private fun updateExternalUi(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastExternalUiUpdateMs < EXTERNAL_UI_INTERVAL_MS) return
        lastExternalUiUpdateMs = now
        if (running) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
        RunWidgetProvider.refresh(this)
        RunTileService.refresh(this)
    }

    override fun onDestroy() {
        instance = null
        spoken.shutdown()
        stopRun(notifyRig = false)
        rigLink.stop()
        super.onDestroy()
    }

    fun connectRig() = rigLink.retryUntilConnected()

    fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasBlePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return hasLocationPermission()
        return androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCellPermission(): Boolean =
        hasLocationPermission() &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---- Start/stop ----

    // notifyRig: true when the phone itself is the one deciding to
    // start/stop (its own button, or the app closing) - sends "scan
    // start"/"scan stop" so the rig follows along. false when reacting to a
    // state change the rig already reported - echoing a command back for
    // something it already knows would just be redundant traffic.
    fun startRun(notifyRig: Boolean) {
        if (running) return
        running = true
        paused = false
        runStartMs = System.currentTimeMillis()
        totalDistanceMeters = 0.0
        lastFixForDistance = null
        excludedCount = 0
        alertedTrackers.clear()
        groups.values.forEach { it.clear() }
        loggedWifiMacsThisRun.clear()
        loggedBleMacsThisRun.clear()
        loggedCellIdsThisRun.clear()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // Blocking-but-brief, once per run start - the old WigleCsvWriter's
        // constructor did the same thing (synchronous file create + header
        // write) directly on the main thread at this exact call site, so
        // this is no worse than the behavior it replaces. Every observation
        // insert after this point is fire-and-forget on dbExecutor instead.
        currentRunId = dbExecutor.submit(Callable {
            dao.insertRun(RunEntity(startedAtMs = runStartMs, label = "phone_$stamp"))
        }).get()
        loadHistoricalIndex()

        if (hasLocationPermission()) locationTracker.start()
        if (hasLocationPermission()) wifiScanner.start()
        if (hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (hasCellPermission()) cellScanner.start()
        if (notifyRig) rigLink.sendScanStart()

        startForeground(NOTIF_ID, buildNotification())
        listener?.onRunStateChanged(true)
        updateExternalUi(force = true)
        spoken.onRunStarted()
    }

    fun stopRun(notifyRig: Boolean) {
        if (!running) return
        running = false
        paused = false
        locationTracker.stop()
        wifiScanner.stop()
        bleScanner.stop()
        cellScanner.stop()
        // A run that starts and stops without ever logging anything (a quick
        // rig reconnect glitch, idle auto-stop right after a false start)
        // shouldn't leave a permanent empty entry behind - same reasoning
        // WigleCsvWriter.close() used to delete a header-only CSV file for.
        val finishedRunId = currentRunId
        currentRunId = null
        if (finishedRunId != null) {
            dbExecutor.execute {
                if (dao.observationsForRun(finishedRunId).isEmpty()) dao.deleteRunWithObservations(finishedRunId)
            }
        }
        if (notifyRig) rigLink.sendScanStop()

        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        listener?.onRunStateChanged(false)
        updateExternalUi(force = true)
        spoken.onRunStopped(wifiCountThisRun, bleCountThisRun)
        if (finishedRunId != null) AutoUploader.maybeUpload(this, finishedRunId)
    }

    // Suspends scanning without ending the run - the run/dedup state/DB row
    // all stay intact, so resuming continues the same session instead of
    // starting a new one. Unlike stop/start, this doesn't touch the rig at
    // all: the rig keeps scanning independently regardless of phone-side
    // pause state, so onObservation() itself is what drops anything -
    // including rig-relayed observations - while paused (see its own
    // `paused` check), rather than trying to tell the rig to also pause.
    fun pauseRun() {
        if (!running || paused) return
        paused = true
        locationTracker.stop()
        wifiScanner.stop()
        bleScanner.stop()
        cellScanner.stop()
        lastFixForDistance = null // avoid a fake distance jump if GPS reacquires somewhere else on resume
        listener?.onPauseStateChanged(true)
    }

    fun resumeRun() {
        if (!running || !paused) return
        paused = false
        if (hasLocationPermission()) locationTracker.start()
        if (hasLocationPermission()) wifiScanner.start()
        if (hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (hasCellPermission()) cellScanner.start()
        listener?.onPauseStateChanged(false)
    }

    fun currentRunId(): Long? = currentRunId

    fun database(): WardriveDao = dao

    // Snapshot taken from whatever's already in the DB *before* this run
    // writes anything - captured once per run start, not kept live, so it
    // reflects "everything logged before now," matching how a human would
    // read "have I seen this before."
    private fun loadHistoricalIndex() {
        Thread {
            historicalIndex = dao.latestPerMac().associateBy({ it.mac }, { it.toHistoricalPoint() })
        }.start()
    }

    // ---- Observation handling (moved from MainActivity verbatim) ----

    private fun onObservation(raw: Observation) {
        if (!running || paused) return // dedup state only makes sense for the current run; paused drops everything, including rig-relayed data, without touching the rig itself

        // DOP-gated logging: HDOP comes from real NMEA GSA sentences (no
        // modern Android location API exposes it directly) - above the
        // configured threshold, positional geometry is too poor to trust
        // (tunnel, urban canyon, no fix yet), so this pauses collection
        // entirely rather than logging observations tagged with a bad fix.
        val maxHdop = appSettings.maxHdop
        if (maxHdop > 0) {
            val hdop = locationTracker.dopSnapshot?.hdop
            if (hdop != null && hdop > maxHdop) return
        }

        // A fix older than this is somewhere we've already driven away from.
        val loc = locationTracker.lastLocation?.takeIf { locationTracker.hasFix() }
        val withLocation = if (loc != null) {
            raw.copy(lat = loc.latitude, lon = loc.longitude, altitudeM = loc.altitude, accuracyM = loc.accuracy.toDouble())
        } else {
            raw
        }
        val previousSighting = historicalIndex[withLocation.mac]
        val tagged = withLocation.copy(
            macRandomized = OuiLookup.isRandomized(withLocation.mac),
            vendor = OuiLookup.vendorFor(withLocation.mac),
            isReturning = previousSighting != null,
        )

        if (isFiltered(tagged)) { excludedCount++; return } // _nomap / blacklist / home exclusion zone - dropped entirely, never shown/logged/uploaded

        val map = groups.getValue(tagged.source)
        val existing = map[tagged.mac]
        val isNewThisRun = existing == null
        // A repeat sighting updates in place (keeps its original position in
        // the LinkedHashMap) but carries forward the running stats a single
        // scanner reading can't know on its own: the MAC's true first-seen
        // time (each scanner stamps `raw` with "now" on every call, so
        // without this every repeat sighting would look like a fresh
        // first-seen), the strongest RSSI seen all run, and a running
        // sightings count - all surfaced in the observation detail view.
        val tagged2 = if (existing != null) {
            tagged.copy(
                firstSeenIso = existing.firstSeenIso,
                timestampMs = existing.timestampMs,
                bestRssi = maxOf(tagged.rssi, existing.bestRssi),
                timesSeenThisRun = existing.timesSeenThisRun + 1,
            )
        } else {
            tagged
        }
        map[tagged.mac] = tagged2

        // Only a genuinely new device this run gets written to the DB -
        // matches the rig's own "log once per run" philosophy instead of
        // inserting a row for the same BLE device's re-advertisement dozens
        // of times. Gated on the cross-source set (not isNewThisRun, which
        // is per-Source) so the same MAC can't get a second row just
        // because the rig and the phone both independently detected it -
        // see the comment on loggedWifiMacsThisRun/loggedBleMacsThisRun.
        // Fire-and-forget on dbExecutor - nothing downstream depends on the
        // insert having completed synchronously, same as the old CSV append.
        //
        // No position (no phone fix yet, and the rig didn't supply one) = shown live but not
        // logged, and not marked as logged either, so it's written the first time it's seen
        // with a fix. Logging it anyway put it at 0,0 - "Null Island" - in every export and upload.
        val runId = currentRunId
        val hasPosition = tagged2.lat != 0.0 || tagged2.lon != 0.0
        val newToDb = hasPosition && when (tagged2.source) {
            Source.RIG_WIFI, Source.PHONE_WIFI -> loggedWifiMacsThisRun.add(tagged2.mac)
            Source.RIG_BLE, Source.PHONE_BLE -> loggedBleMacsThisRun.add(tagged2.mac)
            Source.PHONE_CELL -> loggedCellIdsThisRun.add(tagged2.mac)
        }
        if (newToDb && runId != null) {
            val entity = tagged2.toEntity(runId)
            dbExecutor.execute {
                try {
                    dao.insertObservation(entity)
                } catch (e: Exception) {
                    // A transient DB error shouldn't take the whole app down -
                    // this run just loses that one row instead of crashing.
                }
            }
        }

        // A flagged tracker that's also shown up before, far enough from
        // where it was seen then to mean "traveling with you" rather than
        // "we both happened to pass the same corner once" - at most one
        // alert per MAC per run, not one per observation.
        if (tagged.isTracker && previousSighting != null && tagged.mac !in alertedTrackers &&
            tagged.lat != 0.0 && tagged.lon != 0.0 && previousSighting.lat != 0.0 && previousSighting.lon != 0.0
        ) {
            val movedM = distanceMeters(tagged.lat, tagged.lon, previousSighting.lat, previousSighting.lon)
            if (movedM > PERSISTENT_TRACKER_ALERT_RADIUS_M) {
                alertedTrackers.add(tagged.mac)
                listener?.onPersistentTrackerAlert(tagged, previousSighting)
                spoken.trackerAlert()
            }
        }

        listener?.onObservation(tagged2)
        updateExternalUi()
    }

    // Privacy filters, checked after GPS tagging so the exclusion zone uses
    // the phone's current position (applies uniformly across every source,
    // since they all get the same phone GPS tag). A filtered observation is
    // dropped entirely - not shown, not deduped, not written to CSV, not
    // upload-able - matching how WiGLE's own _nomap convention works.
    private fun isFiltered(obs: Observation): Boolean {
        if (obs.label.endsWith("_nomap", ignoreCase = true)) return true
        if (obs.label.endsWith("_optout", ignoreCase = true)) return true
        if (appSettings.macBlacklist().contains(obs.mac.uppercase())) return true
        if (obs.label.isNotBlank() && appSettings.ssidBlacklist().any { it.equals(obs.label, ignoreCase = true) }) return true

        val radius = appSettings.homeRadiusM
        if (radius > 0 && obs.lat != 0.0 && obs.lon != 0.0) {
            val homeLat = appSettings.homeLat
            val homeLon = appSettings.homeLon
            if (homeLat != 0.0 || homeLon != 0.0) {
                if (distanceMeters(obs.lat, obs.lon, homeLat, homeLon) <= radius) return true
            }
        }
        return false
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Wardrive scanning", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while a wardrive run is actively collecting data"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val miles = totalDistanceMeters / 1609.344
        val text = "WiFi $wifiCountThisRun · BT $bleCountThisRun · %.1f mi".format(Locale.US, miles) +
            if (paused) " · paused" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wardrive run active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentIntent(openAppIntent)
            .addAction(0, "STOP", stopIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    // ---- RigLinkManager.Listener ----
    // None of these are guaranteed to arrive on the main thread (USB I/O
    // callbacks land on RigLinkManager's own executor, not the caller's) -
    // every one is posted through mainHandler so `groups`/`csvWriter`/
    // `running` are only ever touched from one thread, same discipline the
    // old Activity-owned version relied on via runOnUiThread.

    override fun onRigConnected() {
        mainHandler.post {
            rigConnected = true
            // Re-assert scan state on every reconnect, not just the run's original start - a USB
            // drop severe enough to need the reconnect-retry loop can plausibly brown-out/reboot
            // the rig (or just wifi_node/ble_node) independently of the phone, coming back up with
            // scanningActive defaulted to false. cyd_node's own firmware then dutifully "resyncs"
            // to that stale peer state and reports it to the phone, which onRigScanStateChanged()
            // below treats as authoritative and stops the run - a second, independent way a run
            // could end mid-drive beyond the missing-reconnect bug already fixed. Resending "scan
            // start" here is a no-op if the rig was scanning the whole time (setScanning(true) when
            // already true just re-flashes its LED), and corrects the state proactively rather than
            // passively accepting a stale one on any real reconnect.
            if (running) rigLink.sendScanStart()
            listener?.onRigConnected()
        }
    }

    override fun onRigDisconnected() {
        mainHandler.post {
            rigConnected = false
            // Once the USB link itself is down, no more WD:MESHLINK updates can arrive - showing
            // whatever mesh state happened to be last-known would be a stale, possibly-wrong
            // reading with no way to tell it apart from a current one.
            meshLinkState = RigLinkManager.MeshLinkState.DISCONNECTED
            listener?.onRigDisconnected()
            listener?.onMeshLinkStateChanged(meshLinkState)
        }
    }

    override fun onRigObservation(obs: Observation) {
        mainHandler.post { onObservation(obs) }
    }

    override fun onRigStatus(rawLine: String) {
        // Only WD:STATUS carries "ch=" (BEGIN/END don't) - e.g.
        // "WD:STATUS aps=0 bles=0 ch=9 uptime=2m20s". Telemetry-only, the
        // dashboard's CH field just reads lastRigChannel directly.
        if (rawLine.startsWith("WD:STATUS")) {
            Regex("""ch=(\d+)""").find(rawLine)?.groupValues?.get(1)?.toIntOrNull()?.let { lastRigChannel = it }
            fun field(name: String) = Regex("""\b$name=(-?\d+)""").find(rawLine)?.groupValues?.get(1)?.toIntOrNull()
            val gps = field("gps")
            if (gps != null) {
                val health = RigHealth(gps == 1, field("sats") ?: -1, field("sd") == 1, field("pend") ?: 0, System.currentTimeMillis())
                mainHandler.post { rigHealth = health }
            }
        }
    }

    override fun onRigLog(line: String) {
        mainHandler.post { listener?.onRigLogLine(line) }
    }

    override fun onMeshLinkStateChanged(state: RigLinkManager.MeshLinkState) {
        mainHandler.post {
            meshLinkState = state
            listener?.onMeshLinkStateChanged(state)
        }
    }

    override fun onRigScanStateChanged(active: Boolean) {
        mainHandler.post {
            // Mirror whatever the rig just reported - a physical button
            // press, idle auto-stop, or an SD failure aborting a
            // phone-requested start. This now happens inside the service
            // itself, so it works correctly even while the phone's screen
            // is locked and no Activity is bound - notifyRig=false since
            // the rig already knows its own state.
            if (active && !running) startRun(notifyRig = false)
            else if (!active && running) stopRun(notifyRig = false)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wardrive_scan"
        private const val NOTIF_ID = 1
        // How far the current sighting has to be from where a flagged
        // tracker was last logged before it's treated as "traveling with
        // you" rather than "same spot as last time" - GPS/RSSI-position
        // noise alone can easily be tens of meters, so this stays well
        // above that.
        private const val PERSISTENT_TRACKER_ALERT_RADIUS_M = 500.0
        private const val GNSS_PERSIST_INTERVAL_MS = 15000L

        const val ACTION_START = "com.dreknil.wardrivebridge.START_RUN"
        const val ACTION_STOP = "com.dreknil.wardrivebridge.STOP_RUN"
        private const val EXTERNAL_UI_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            context.startService(Intent(context, ScanService::class.java))
        }

        /** Stops a run from outside the app (tile, widget). Returns false if nothing was running,
         *  in which case the caller should open the app with startRunIntent() instead: Android 14+
         *  won't let a location service start from the background, so a run has to start from the
         *  app's own screen. */
        fun stopRunIfRunning(): Boolean {
            val s = instance ?: return false
            if (!s.running) return false
            s.stopRun(notifyRig = true)
            return true
        }

        fun startRunIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The live service, for BridgeProvider - main-thread state, so read it on the main thread. */
        @Volatile
        var instance: ScanService? = null
            private set
    }
}
