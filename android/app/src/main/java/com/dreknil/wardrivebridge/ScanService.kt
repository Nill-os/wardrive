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
import android.os.PowerManager
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
    private lateinit var uploadRelay: RigUploadRelay
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

    // ---- Meshtastic nodes from the user's own LoRa radio (see MeshtasticRadioLink) ----
    private lateinit var meshLink: MeshtasticRadioLink
    private lateinit var meshBridge: MeshBridgeServer // lets the Meshtastic app share the radio during runs
    private val meshNodesThisRun = HashMap<Long, MeshNodeEntity>()
    var meshNodeCountThisRun = 0; private set
    // The user's own radio's node number(s) - never a find. Confirmed numbers (from my_info or
    // the first NodeInfo after a nodes-only request) are remembered per radio across runs.
    private val meshOwnNums = HashSet<Long>()
    // True once the radio has sent my_info on the current connection: no node is logged before the
    // radio has said which node it is (MeshtasticRadioLink asks for it first on every connect).
    private var meshIdentified = false
    // Paused stretches of this run (epoch seconds): a node the radio heard only then wasn't part of
    // the run, even if a later node-list read reports it.
    private val pausedSpans = mutableListOf<LongRange>()
    private var pausedSinceSec = 0L
    private fun heardWhilePaused(lastHeardSec: Long): Boolean = pausedSpans.any { lastHeardSec in it }
    private var meshSyncNodes = 0 // NodeInfos in the current node-list read, for the log line
    val meshRadioConnected get() = ::meshLink.isInitialized && meshLink.isConnected
    val meshAppBridged get() = ::meshBridge.isInitialized && meshBridge.hasClient
    private val meshCallbacks = object : MeshtasticRadioLink.Callbacks {
        override fun onMeshConnected() {
            meshBridge.dropClient() // a bridge client never spans two radio connections
            logRunEvent("mesh radio connected")
        }
        override fun onMeshDisconnected() {
            meshIdentified = false
            logRunEvent("mesh radio disconnected")
            meshBridge.dropClient() // the Meshtastic app reconnects and re-reads once the radio is back
        }
        override fun onMeshLog(msg: String) { listener?.onRigLogLine(msg) }
        override fun onMeshMessage(msg: MeshProto.Message) {
            if (msg is MeshProto.Message.MyInfo) {
                val own = msg.nodeNum
                meshSyncNodes = 0
                if (own == 0L) return
                if (!meshIdentified) listener?.onRigLogLine("[mesh] radio is !%08x - its own node is never logged as a find".format(own))
                meshIdentified = true
                appSettings.setMeshOwnNode(meshLink.radioAddress, own)
                if (meshOwnNums.add(own)) {
                    if (meshNodesThisRun.remove(own) != null) meshNodeCountThisRun--
                    dbExecutor.execute { dao.deleteMeshNodeEverywhere(own) }
                }
                return
            }
            if (msg is MeshProto.Message.NodeInfo) meshSyncNodes++
            if (msg is MeshProto.Message.ConfigComplete) {
                if (msg.id == MeshProto.NONCE_ONLY_NODES.toLong()) // not the Meshtastic app's config-only read
                    listener?.onRigLogLine("[mesh] node list read: radio knows $meshSyncNodes nodes, $meshNodeCountThisRun heard over the air this run")
                meshSyncNodes = 0
                return
            }
            if (paused || !meshIdentified) return
            val runId = currentRunId ?: return
            when (msg) {
                // The radio's node list also holds nodes it heard days ago or only over the internet
                // (MQTT): only count ones it heard over the air since this run started. The user's
                // own radio isn't a find. (The upper bound: a radio clock running ahead must not pass
                // old nodes off as new.)
                is MeshProto.Message.NodeInfo -> if (msg.node.num !in meshOwnNums && !msg.node.viaMqtt &&
                    msg.node.lastHeard >= runStartMs / 1000 - 60 && msg.node.lastHeard <= System.currentTimeMillis() / 1000 + 60 &&
                    !heardWhilePaused(msg.node.lastHeard)) mergeMeshNode(runId, msg.node.num, msg.node, heardNow = false)
                is MeshProto.Message.Heard -> if (msg.from != 0L && msg.from !in meshOwnNums && !msg.viaMqtt) {
                    // The radio queues packets while no app is connected, so a packet can be old:
                    // judge it by when the radio received it, not when it reached the phone.
                    val nowSec = System.currentTimeMillis() / 1000
                    val rxKnown = msg.rxTime > VALID_EPOCH_SEC
                    val keep = if (rxKnown) msg.rxTime >= runStartMs / 1000 - 60 && msg.rxTime <= nowSec + 60 && !heardWhilePaused(msg.rxTime)
                               else !msg.backlog // unknown receive time + from the queue: can't place it in time
                    if (keep) {
                        // By the radio's receive time when known (a packet can also wait behind a config
                        // stream); otherwise only a packet read after the queued backlog counts as fresh.
                        val fresh = if (rxKnown) nowSec - msg.rxTime in -FRESH_PACKET_SEC..FRESH_PACKET_SEC else !msg.backlog
                        mergeMeshNode(runId, msg.from,
                            MeshProto.Node(msg.from, user = msg.user, position = msg.position, snr = msg.snr,
                                lastHeard = if (rxKnown) msg.rxTime else 0, hopsAway = msg.hopsAway),
                            heardNow = fresh, direct = msg.hopsAway == 0) // the phone's position only for a fresh, direct packet
                    }
                }
                else -> {}
            }
        }
    }

    /** Folds a radio report into this run's row for that node: keeps whatever is already known
     *  (name, position) when the new report lacks it, and stamps where this phone was when the
     *  node was heard live. */
    private fun ownMeshNodeNum(address: String): Long? {
        val hex = address.replace(":", "")
        return if (hex.length == 12) hex.takeLast(8).toLongOrNull(16) else null
    }

    private fun mergeMeshNode(runId: Long, num: Long, n: MeshProto.Node, heardNow: Boolean, direct: Boolean = false) {
        if (num == 0L || num == 0xffffffffL) return // not a node: unset / the broadcast address
        val prev = meshNodesThisRun[num]
        // Where the phone was only stands in for the node's position when the node was heard
        // directly - a packet relayed over several hops may come from miles away.
        val here = if (heardNow && direct) locationTracker.lastLocation?.takeIf { locationTracker.hasFix() } else null
        val pos = n.position
        val merged = MeshNodeEntity(
            id = prev?.id ?: 0,
            runId = runId,
            nodeNum = num,
            // From the node number (packet sender / node DB key), never the broadcast user.id - a
            // node can put any id in its own NodeInfo, e.g. the user's radio's.
            nodeId = "!%08x".format(num),
            longName = n.user?.longName?.ifBlank { null } ?: prev?.longName ?: "",
            shortName = n.user?.shortName?.ifBlank { null } ?: prev?.shortName ?: "",
            hwModel = n.user?.hwModel ?: prev?.hwModel ?: 0,
            lat = pos?.lat ?: prev?.lat ?: 0.0,
            lon = pos?.lon ?: prev?.lon ?: 0.0,
            altitudeM = pos?.altitudeM ?: prev?.altitudeM ?: 0,
            positionTime = pos?.time ?: prev?.positionTime ?: 0,
            lastHeard = if (heardNow) System.currentTimeMillis() / 1000 else maxOf(n.lastHeard, prev?.lastHeard ?: 0),
            snr = if (n.snr != 0f) n.snr else prev?.snr ?: 0f,
            hopsAway = if (n.hopsAway >= 0) n.hopsAway else prev?.hopsAway ?: -1,
            viaMqtt = if (heardNow) false else n.viaMqtt,
            heardLat = here?.latitude ?: prev?.heardLat ?: 0.0,
            heardLon = here?.longitude ?: prev?.heardLon ?: 0.0,
            heardAtMs = if (here != null) System.currentTimeMillis() else prev?.heardAtMs ?: 0,
            updatedAtMs = System.currentTimeMillis(),
        )
        if (prev == null) meshNodeCountThisRun++
        meshNodesThisRun[num] = merged
        dbExecutor.execute { dao.upsertMeshNode(merged) }
    }

    var running = false; private set
    private var runWakeLock: PowerManager.WakeLock? = null

    private fun releaseRunWakeLock() {
        try { if (runWakeLock?.isHeld == true) runWakeLock?.release() } catch (_: Exception) {}
    }
    // True while a run is active but scanning is temporarily suspended -
    // distinct from `running`, which stays true the whole time (pausing
    // doesn't end or finalize the run the way stopRun() does). Only
    // meaningful when running is true; startRun()/stopRun() always clear it.
    var paused = false; private set
    // Live "preview" scanning for the detection/live-feed tools: the phone's
    // radios are on and observations flow into `groups` (so the tools show
    // what's around right now), but there's NO logged run - no GPS, no DB rows,
    // no foreground service, no rig involvement. This is what makes "Live WiFi",
    // "Pineapple Detection" etc. actually show something when you open them
    // without first starting a full drive. A real run always takes precedence.
    var previewing = false; private set
    private var previewWifi = false
    private var previewBle = false
    private var previewCell = false
    private var previewOwnsRigScan = false // preview told the rig to scan; stop it when preview ends
    var rigConnected = false; private set
    var meshLinkState = RigLinkManager.MeshLinkState.DISCONNECTED; private set
    // Parsed out of the rig's own periodic "WD:STATUS ... ch=N ..." line (see
    // onRigStatus()) - the phone's own WiFi scan has no single "active
    // channel" the way the rig's channel-hopping sniffer does, so this is
    // rig-only telemetry, 0 until the first status line ever arrives.
    var lastRigChannel = 0; private set

    /** The rig's own health, from the extra fields in its WD:STATUS line (newer firmware). */
    data class RigHealth(
        val gpsFix: Boolean, val sats: Int, val sdOk: Boolean, val pendingUploads: Int, val atMs: Long,
        /** The CYD screen's own WIGLE / BT numbers for its current run (null on older firmware). */
        val rigWifi: Int? = null, val rigBle: Int? = null,
        /** Self-telemetry from newer firmware (null on older builds): free RAM (KB),
         *  free SD space (MB, -1 if unknown), and whether the wifi_node link is up. */
        val freeHeapKB: Int? = null, val sdFreeMB: Int? = null, val wifiNodeUp: Boolean? = null,
        /** Whether the rig is mid-run (the status line's scan=); null on older firmware. */
        val scanning: Boolean? = null,
    )
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
    // Counts UNIQUE excluded devices, not every excluded observation. Each MAC
    // is re-seen on every scan cycle, so counting per-observation made this climb
    // forever even while stationary; counting per-MAC means it settles once every
    // nearby filtered device has been seen once.
    var excludedCount = 0; private set
    private val excludedMacs = HashSet<String>()

    // Snapshot of every device ever logged in a *previous* run, taken once
    // at startRun() (before this run's own CSV exists) - see
    // SessionListener.onPersistentTrackerAlert and Observation.isReturning.
    // @Volatile since it's written once from the background-load thread and
    // read from the main thread on every observation - a simple
    // publish-once reference swap, but needs the visibility guarantee.
    @Volatile private var historicalIndex: Map<String, HistoricalPoint> = emptyMap()
    private val alertedTrackers = mutableSetOf<String>()
    // "category|mac" already alerted this run, so each notable device speaks once.
    private val alertedDetections = mutableSetOf<String>()

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

    // Devices logged this run WITHOUT a GPS fix (rows at 0,0). Tracked so a later
    // fixed sighting of the same device can be upgraded to a positioned row, and
    // so these can be counted for the "logging without a fix" warning.
    private val noFixWifiMacsThisRun = mutableSetOf<String>()
    private val noFixBleMacsThisRun = mutableSetOf<String>()
    private val noFixCellIdsThisRun = mutableSetOf<String>()

    private val spoken by lazy { SpokenUpdates(this, appSettings) { wifiCountThisRun to bleCountThisRun } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appSettings = AppSettings(this)
        dao = AppDatabase.get(this).dao()
        rigLink = RigLinkManager(applicationContext, this)
        meshLink = MeshtasticRadioLink(applicationContext, meshCallbacks)
        meshBridge = MeshBridgeServer(meshLink) { listener?.onRigLogLine(it) }
        meshLink.onFromRadioRaw = { meshBridge.forward(it) }
        uploadRelay = RigUploadRelay(
            applicationContext, appSettings,
            send = { line -> rigLink.sendRaw(line) },
            log = { msg -> mainHandler.post { listener?.onRigLogLine(msg) } },
            onProgress = { up, failed -> mainHandler.post { listener?.onRigLogLine("[relay] $up uploaded, $failed failed") } },
        )
        locationTracker = LocationTracker(applicationContext)
        locationTracker.onNewFix = { loc ->
            lastFixForDistance?.let { prev -> if (running) totalDistanceMeters += prev.distanceTo(loc) }
            lastFixForDistance = loc
            listener?.onLocationChanged(loc)
            // The rig falls back to this position when its own GPS has no fix (see the CYD's
            // phonePosition()); it ignores anything over 50 m accuracy or older than 5 s.
            if (loc.hasAccuracy() && loc.accuracy <= 50f) mainHandler.post {
                if (rigConnected) rigLink.sendRaw(String.format(Locale.US, "app pos %.6f %.6f %.1f %.1f",
                    loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0, loc.accuracy.toDouble()))
            }
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
        mainHandler.postDelayed(relayTicker, 10_000)
        mainHandler.postDelayed(watchSweep, WATCH_SWEEP_MS)
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
            // A run with only Meshtastic nodes (no WiFi/BLE/cell) isn't empty - keep it to upload.
            dao.allRunSummaries().filter { it.count == 0 && dao.meshNodeCount(it.id) == 0 }.forEach { dao.deleteRunWithObservations(it.id) }
        }
        val days = appSettings.retentionDays
        if (days <= 0) return
        val cutoffMs = System.currentTimeMillis() - days * 24L * 3600L * 1000L
        dbExecutor.execute { dao.deleteRunsOlderThan(cutoffMs) }
    }

    // The notification's STOP button reaches the service through this action.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (running) stopRun(notifyRig = true, reason = "notification STOP")
        }
        return START_STICKY
    }

    /** Why each run started and stopped, with a timestamp - in the Terminal tab and in
     *  files/run_events.log (last ~500 lines), so a run that splits mid-drive can be traced. */
    private fun logRunEvent(event: String) {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} $event"
        listener?.onRigLogLine("[run] $event")
        dbExecutor.execute { // file I/O off the main thread (radio connects/disconnects log here too)
            try {
                val f = java.io.File(filesDir, "run_events.log")
                val kept = if (f.exists()) f.readLines().takeLast(499) else emptyList()
                f.writeText((kept + line).joinToString("\n", postfix = "\n"))
            } catch (_: Exception) {
            }
        }
    }

    private fun showJoinPrompt() {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
        val tap = PendingIntent.getActivity(this, 2, startRunIntent(this), flags)
        val n = NotificationCompat.Builder(this, ATTENTION_CHANNEL_ID)
            .setContentTitle("The rig is scanning")
            .setContentText("Tap to join its run")
            .setSmallIcon(R.drawable.ic_radar)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(JOIN_NOTIF_ID, n)
    }

    /** A run couldn't start because Location is off for the app - say so (opens the app, which asks). */
    private fun showLocationNeeded() {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0)
        val tap = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).setAction(ACTION_ASK_LOCATION), flags) // MainActivity asks for it
        val n = NotificationCompat.Builder(this, ATTENTION_CHANNEL_ID)
            .setContentTitle("Can't start a run")
            .setContentText("Allow Location for Nill OS - Wardriver")
            .setSmallIcon(R.drawable.ic_radar)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(JOIN_NOTIF_ID, n)
    }

    /** Tells the rig to upload its SD-card runs now. False if no rig is connected. */
    fun requestRigUpload(): Boolean {
        if (!rigConnected) return false
        rigLink.sendRigUpload()
        return true
    }

    /**
     * THE run counts - unique WiFi / BLE devices this run - shared by every surface
     * (dashboard, Organic Maps overlay, widget, tile, notification, speech) and sent
     * to the rig so its screen shows the same numbers. Each is the larger of the
     * phone's union of rig + phone devices and the rig's own unique count (the rig
     * can hold devices a dropped link never relayed). The rig applies the same max()
     * to the number we send it, so all three always agree.
     */
    val wifiCountThisRun: Int
        get() = maxOf((groups.getValue(Source.RIG_WIFI).keys + groups.getValue(Source.PHONE_WIFI).keys).size, rigReportedCount { it.rigWifi })
    val bleCountThisRun: Int
        get() = maxOf((groups.getValue(Source.RIG_BLE).keys + groups.getValue(Source.PHONE_BLE).keys).size, rigReportedCount { it.rigBle })

    /** The rig's own count, only while it's mid-run and its report is fresh (else a
     *  finished run's last number would leak into the next one). */
    private fun rigReportedCount(pick: (RigHealth) -> Int?): Int {
        val h = rigHealth ?: return 0
        if (h.scanning == false || System.currentTimeMillis() - h.atMs > 15_000) return 0
        return pick(h) ?: 0
    }

    /** True when there's a position fresh enough to log with (phone's own fix). */
    fun hasFix(): Boolean = ::locationTracker.isInitialized && locationTracker.hasFix()

    /** Devices logged this run WITHOUT a position (0,0) - kept locally but never
     *  uploaded/exported. Drives the "logging without a GPS fix" warning. */
    val noFixCountThisRun: Int
        get() = noFixWifiMacsThisRun.size + noFixBleMacsThisRun.size + noFixCellIdsThisRun.size

    // Devices logged this run whose MAC was in no previous run at all - i.e.
    // new to this phone's whole collection, the "new finds" WiGLE-style number
    // that makes a drive feel worthwhile. Counted locally against the
    // historicalIndex snapshot taken at startRun(), so it's instant and exact
    // for our own data (WiGLE's own credited-new count still lives in the
    // Analytics account stats, which only it can know).
    private var newThisRunCount = 0
    val newThisRun: Int get() = newThisRunCount

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

    // Drives the upload relay: re-checks the rig for pending files and times out stalls.
    private val relayTicker = object : Runnable {
        override fun run() {
            if (::uploadRelay.isInitialized) uploadRelay.onIdle()
            mainHandler.postDelayed(this, 10_000)
        }
    }

    override fun onDestroy() {
        releaseRunWakeLock()
        if (::meshBridge.isInitialized) meshBridge.shutdown()
        instance = null
        mainHandler.removeCallbacks(relayTicker)
        mainHandler.removeCallbacks(watchSweep)
        spoken.shutdown()
        stopRun(notifyRig = false, reason = "app closed")
        rigLink.stop()
        meshBridge.stop()
        meshLink.stop()
        super.onDestroy()
    }

    fun connectRig() = rigLink.retryUntilConnected()

    fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // What Android requires for a location-type foreground service: fine OR approximate.
    private fun hasAnyLocationPermission(): Boolean = hasLocationPermission() ||
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
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
    private var meshStarted = false // the mesh link was started for the current run

    /** Starts the Meshtastic link (and bridge) for the run if it's set up and permitted - at run
     *  start, or later when the Bluetooth permission is granted mid-run. */
    private fun startMeshIfConfigured() {
        if (meshStarted || !(appSettings.meshCollect && appSettings.meshRadioAddress.isNotEmpty() && hasBlePermission())) return
        meshStarted = true
        // Keeps the user's own radio - and its position, i.e. where they are - out of the finds.
        meshOwnNums.clear()
        meshIdentified = false
        // Best guess before the radio confirms it: the low 4 bytes of its Bluetooth address
        // (right on nRF52 radios), plus every own number radios have confirmed on earlier runs.
        ownMeshNodeNum(appSettings.meshRadioAddress)?.let { meshOwnNums.add(it) }
        // Every radio the user has linked is theirs (e.g. an old radio kept at home as a base):
        // none of them is ever a find, and none of their rows stays in the log.
        for (confirmed in appSettings.meshOwnNodeNums()) {
            meshOwnNums.add(confirmed)
            dbExecutor.execute { dao.deleteMeshNodeEverywhere(confirmed) }
        }
        meshLink.start(appSettings.meshRadioAddress)
        if (appSettings.meshBridge) meshBridge.start()
    }

    /** Permissions granted while a run is going (e.g. Nearby devices / Phone right after Location):
     *  start whatever they unlock now instead of waiting for the next run. Safe to call repeatedly. */
    fun applyGrantedPermissions() {
        if (!running || paused) return
        if (hasLocationPermission()) { locationTracker.start(); wifiScanner.start() }
        if (hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (hasCellPermission()) cellScanner.start()
        startMeshIfConfigured()
    }

    fun startRun(notifyRig: Boolean, reason: String = "app", fromUi: Boolean = false) {
        if (running) return
        // Android 14+ refuses to start a location foreground service while the app is in the
        // background (pocket, screen off). Try it first; if refused, ask the user to tap in
        // rather than crashing - the tap opens the app, which starts the run.
        if (!hasAnyLocationPermission()) { // the location service type needs it - not a background issue
            logRunEvent("can't start a run ($reason): Location permission is off")
            listener?.onRigLogLine("[run] allow Location for this app to start runs")
            if (!fromUi) showLocationNeeded() // the in-app START asks right away instead
            return
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            logRunEvent("couldn't start in the background ($reason) - asking to tap in")
            showJoinPrompt()
            return
        }
        // Keep the CPU (not the screen) awake for the run: the foreground service keeps the app
        // alive, but with the screen off the phone still sleeps between events, stalling the
        // timed work (WiFi cache reads, cell scans, radio refreshes). Released in stopRun().
        try {
            runWakeLock = runWakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NillOS:wardrive-run").apply { setReferenceCounted(false) }
            runWakeLock?.acquire(RUN_WAKELOCK_MAX_MS)
        } catch (_: Exception) {
        }
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(JOIN_NOTIF_ID)
        logRunEvent("run started ($reason)")
        running = true
        paused = false
        previewing = false // a real run supersedes any live-preview scanning
        previewOwnsRigScan = false // the run now owns the rig's scan state
        runStartMs = System.currentTimeMillis()
        totalDistanceMeters = 0.0
        lastFixForDistance = null
        excludedCount = 0
        excludedMacs.clear()
        alertedTrackers.clear()
        alertedDetections.clear()
        groups.values.forEach { it.clear() }
        loggedWifiMacsThisRun.clear()
        loggedBleMacsThisRun.clear()
        loggedCellIdsThisRun.clear()
        noFixWifiMacsThisRun.clear()
        noFixBleMacsThisRun.clear()
        noFixCellIdsThisRun.clear()
        newThisRunCount = 0
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

        if (!hasLocationPermission()) logRunEvent("precise Location is off - phone GPS, WiFi and cell aren't scanning this run")
        if (hasLocationPermission()) locationTracker.start()
        if (hasLocationPermission()) wifiScanner.start()
        if (hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (hasCellPermission()) cellScanner.start()
        meshNodesThisRun.clear()
        meshNodeCountThisRun = 0
        pausedSpans.clear()
        pausedSinceSec = 0L
        meshStarted = false
        startMeshIfConfigured()
        if (notifyRig) rigLink.sendScanStart()

        startForeground(NOTIF_ID, buildNotification())
        listener?.onRunStateChanged(true)
        updateExternalUi(force = true)
        spoken.onRunStarted()
    }

    fun stopRun(notifyRig: Boolean, reason: String = "app") {
        if (!running) return
        logRunEvent("run stopped ($reason)")
        running = false
        paused = false
        locationTracker.stop()
        wifiScanner.stop()
        bleScanner.stop()
        cellScanner.stop()
        meshBridge.stop()
        meshLink.stop() // frees the radio for the Meshtastic app again
        meshStarted = false
        pausedSpans.clear()
        pausedSinceSec = 0L
        if (meshNodeCountThisRun > 0) logRunEvent("mesh: $meshNodeCountThisRun nodes this run")
        // A run that starts and stops without ever logging anything (a quick
        // rig reconnect glitch, idle auto-stop right after a false start)
        // shouldn't leave a permanent empty entry behind - same reasoning
        // WigleCsvWriter.close() used to delete a header-only CSV file for.
        val finishedRunId = currentRunId
        currentRunId = null
        if (finishedRunId != null) {
            dbExecutor.execute {
                if (dao.observationsForRun(finishedRunId).isEmpty() && dao.meshNodeCount(finishedRunId) == 0) dao.deleteRunWithObservations(finishedRunId)
            }
        }
        if (notifyRig) rigLink.sendScanStop()

        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        releaseRunWakeLock()
        listener?.onRunStateChanged(false)
        updateExternalUi(force = true)
        spoken.onRunStopped(wifiCountThisRun, bleCountThisRun)
        // Queued behind the run's last observation / mesh-node writes on the same single-thread
        // executor, so the upload never misses the final seconds of the run.
        if (finishedRunId != null) dbExecutor.execute { AutoUploader.maybeUpload(this, finishedRunId) }
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
        releaseRunWakeLock() // nothing to keep awake for while paused
        pausedSinceSec = System.currentTimeMillis() / 1000
        listener?.onPauseStateChanged(true)
    }

    fun resumeRun() {
        if (!running || !paused) return
        paused = false
        if (hasLocationPermission()) locationTracker.start()
        if (hasLocationPermission()) wifiScanner.start()
        if (hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (hasCellPermission()) cellScanner.start()
        try { runWakeLock?.acquire(RUN_WAKELOCK_MAX_MS) } catch (_: Exception) {}
        if (pausedSinceSec > 0) pausedSpans.add(pausedSinceSec..System.currentTimeMillis() / 1000)
        pausedSinceSec = 0L
        startMeshIfConfigured() // e.g. Nearby devices granted while paused
        listener?.onPauseStateChanged(false)
    }

    // Turn the phone's radios on for the live/detection tools without starting a
    // logged run. No GPS (positions aren't needed just to see what's nearby), no
    // DB rows (currentRunId stays null, so onObservation never writes), no
    // foreground service. A real run always wins: if one is active this is a
    // no-op and the run's own scanning already feeds the same tools.
    // wifi/ble/cell pick which radios to power up - a WiFi tool doesn't need
    // the BLE radio and vice-versa, so opening "Live WiFi" only turns on WiFi
    // scanning ("search for the selected tool"). If the open tool changes type,
    // MainActivity restarts preview with the new set.
    //
    // The rig is also asked to scan (if connected) so its sightings stream into
    // the same tools, not just the phone's. The rig only emits WD:AP/WD:BLE
    // while it's scanning, so we start it here and stop it in stopPreview - but
    // WITHOUT it turning into a phone run (onRigScanStateChanged skips its
    // auto-start-run while previewing). If the rig were already scanning on its
    // own, the phone would already be in a run and this whole path is skipped.
    fun startPreview(wifi: Boolean = true, ble: Boolean = true, cell: Boolean = false) {
        if (running) return
        if (previewing) stopPreview() // switching tool type: drop the old radio set first
        previewing = true
        previewWifi = wifi; previewBle = ble; previewCell = cell
        // Don't wipe groups here: clearing then waiting for the first scan is
        // what made a reopened tool flash empty. The scanners re-emit the OS's
        // cached results immediately on start(), updating entries in place.
        //
        // Location tracking has to be on even though we don't log positions in a
        // preview: Android only hands WifiManager.getScanResults() to an app
        // that is actively using location, so without this the WiFi list comes
        // back empty (the whole "Live WiFi doesn't work" symptom). It also lets
        // BLE/WiFi readings carry a position if a tool ever wants one.
        if (wifi && hasLocationPermission()) locationTracker.start()
        if (wifi && hasLocationPermission()) wifiScanner.start()
        if (ble && hasBlePermission() && bleScanner.isSupported()) bleScanner.start()
        if (cell && hasCellPermission()) cellScanner.start()
        if (rigConnected && (wifi || ble)) { rigLink.sendScanStart(); previewOwnsRigScan = true }
        updateExternalUi(force = true)
    }

    fun stopPreview() {
        if (!previewing) return
        previewing = false
        if (running) return // a run took over the radios; leave them on for it
        if (previewOwnsRigScan) { rigLink.sendScanStop(); previewOwnsRigScan = false }
        if (previewWifi) wifiScanner.stop()
        if (previewBle) bleScanner.stop()
        if (previewCell) cellScanner.stop()
        locationTracker.stop() // was only started to unlock WiFi scan results for the preview
        previewWifi = false; previewBle = false; previewCell = false
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
        if ((!running && !previewing) || paused) return // no run and no live preview open = nothing to collect; paused drops everything, including rig-relayed data, without touching the rig itself

        checkWatchlist(raw) // notify on watched devices entering range - runs for every sighting, before any filtering

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

        // _nomap / blacklist / home exclusion zone. These are still SHOWN in the
        // live tools/feed (so Live WiFi, fox-hunt and antenna check work at home,
        // inside your own exclusion zone), but a filtered sighting is never logged,
        // counted, exported, uploaded or alerted on - that's the privacy guarantee
        // that actually matters. See the short-circuit right after the group add.
        val filtered = isFiltered(tagged)
        if (filtered && excludedMacs.add(tagged.mac)) excludedCount++ // unique excluded devices, for the dashboard

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

        // Filtered (home zone / _nomap / blacklist): shown live above, but stop
        // here - no DB row, no new-find tally, no alert, nothing that persists
        // or leaves the device. The live tools still update via the listener.
        if (filtered) {
            listener?.onObservation(tagged2)
            updateExternalUi()
            return
        }

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
        // Logging without a GPS fix: the device is still recorded (kept locally so
        // no data is lost - the user asked to keep gathering with a warning), but at
        // 0,0. Those fix-less rows are flagged (below) and excluded from every upload
        // and export, so nothing lands at "Null Island" on WiGLE. If the same device
        // is seen again once there IS a fix, it's logged a second time with a real
        // position (the "upgrade" below), and that positioned row is what uploads.
        val runId = currentRunId
        val hasPosition = tagged2.lat != 0.0 || tagged2.lon != 0.0
        val loggedSet = when (tagged2.source) {
            Source.RIG_WIFI, Source.PHONE_WIFI -> loggedWifiMacsThisRun
            Source.RIG_BLE, Source.PHONE_BLE -> loggedBleMacsThisRun
            Source.PHONE_CELL -> loggedCellIdsThisRun
        }
        val noFixSet = when (tagged2.source) {
            Source.RIG_WIFI, Source.PHONE_WIFI -> noFixWifiMacsThisRun
            Source.RIG_BLE, Source.PHONE_BLE -> noFixBleMacsThisRun
            Source.PHONE_CELL -> noFixCellIdsThisRun
        }
        val firstLog = loggedSet.add(tagged2.mac)                                   // first time this MAC is logged this run
        if (firstLog && !hasPosition) noFixSet.add(tagged2.mac)                      // remember it was logged fix-less
        val upgrade = !firstLog && hasPosition && noFixSet.remove(tagged2.mac)       // now we have a position for a fix-less MAC
        val newToDb = firstLog || upgrade
        // Count a "new find" only on the genuine first sighting, never again on the
        // fix-less -> positioned upgrade (that's the same device, a second row).
        if (running && firstLog && !historicalIndex.containsKey(tagged2.mac)) newThisRunCount++ // live preview doesn't touch the run's new-find tally
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

        maybeDetectionAlert(tagged2)
        listener?.onObservation(tagged2)
        updateExternalUi()
    }

    // Per-category "heads up, X nearby" alert (spoken + vibrate), once per
    // device per run, gated on each category's own toggle. These fire on the
    // phone; the rig raises its own on-screen/LED alert from the same toggles
    // (pushed to it as cfg alert_* keys) so you're covered with or without the
    // phone in hand.
    private fun maybeDetectionAlert(o: Observation) {
        val hits = buildList {
            if (appSettings.alertFlock && o.isFlockCamera) add("Flock camera")
            if (appSettings.alertPolice && o.isPoliceCam) add("police camera")
            if (appSettings.alertSkimmer && o.isSkimmer) add("possible skimmer")
            if (appSettings.alertFlipper && o.isFlipperZero) add("Flipper Zero")
            if (appSettings.alertDrone && o.isDrone) add("drone")
            if (appSettings.alertMesh && o.isMeshRadio) add("mesh radio")
            if (appSettings.alertGlasses && o.isGlasses) add("smart glasses")
            if (appSettings.alertActionCam && o.isActionCam) add("action camera")
            if (appSettings.alertPineapple && o.isPineapple) add("WiFi Pineapple")
        }
        for (what in hits) {
            if (alertedDetections.add("$what|${o.mac}")) {
                spoken.detectionAlert(what)
                vibrateAlert()
            }
        }
    }

    private fun vibrateAlert() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(250)
        } catch (_: Exception) {}
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
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Wardrive scanning", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while a wardrive run is actively collecting data"
        nm.createNotificationChannel(channel)
        // Higher-importance channel so watchlist hits pop as a heads-up.
        nm.createNotificationChannel(NotificationChannel(ATTENTION_CHANNEL_ID, "Run needs attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A run the rig started needs a tap to join, or can't start without Location"
        })
        val watch = NotificationChannel(WATCH_CHANNEL_ID, "Watchlist alerts", NotificationManager.IMPORTANCE_HIGH)
        watch.description = "A watched WiFi/BLE device came into range or left"
        nm.createNotificationChannel(watch)
    }

    // ---- Watchlist: notify when specific devices enter / leave range ----

    private val watchLastSeen = HashMap<String, Long>() // watch entry -> last time a matching device was seen
    private val watchLastNotify = HashMap<String, Long>() // watch entry -> last time we posted an enter/leave notification
    private var watchNotifId = 4000

    private fun watchMatches(key: String, obs: Observation): Boolean =
        if (key.contains(":")) obs.mac.equals(key, ignoreCase = true)
        else obs.label.contains(key, ignoreCase = true) || obs.mac.contains(key, ignoreCase = true)

    // Rate-limit per entry so a device hovering at the edge of the leave window
    // can't post a stream of enter/leave alerts.
    private fun watchCanNotify(key: String, now: Long): Boolean {
        val last = watchLastNotify[key]
        if (last != null && now - last < WATCH_NOTIFY_COOLDOWN_MS) return false
        watchLastNotify[key] = now
        return true
    }

    private fun checkWatchlist(obs: Observation) {
        val entries = appSettings.watchEntries()
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        for (e in entries) {
            if (!watchMatches(e.key, obs)) continue
            val wasPresent = watchLastSeen.containsKey(e.key)
            watchLastSeen[e.key] = now
            if (!wasPresent && e.enter && watchCanNotify(e.key, now)) {
                watchNotify("Watchlist: in range", "${e.label}  ·  ${obs.label.ifBlank { obs.mac }}  ·  ${obs.rssi} dBm")
            }
        }
    }

    // Runs on mainHandler; a watched device not seen for WATCH_LEAVE_MS is "gone".
    private val watchSweep = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val entries = appSettings.watchEntries()
            val it = watchLastSeen.entries.iterator()
            while (it.hasNext()) {
                val (key, last) = it.next()
                if (now - last > WATCH_LEAVE_MS) {
                    it.remove()
                    val e = entries.firstOrNull { it.key == key }
                    if (e != null && e.leave && watchCanNotify(key, now)) watchNotify("Watchlist: left range", e.label)
                }
            }
            mainHandler.postDelayed(this, WATCH_SWEEP_MS)
        }
    }

    private fun watchNotify(title: String, text: String) {
        vibrateAlert()
        try {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0),
            )
            val n = NotificationCompat.Builder(this, WATCH_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_radar)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(watchNotifId++, n)
        } catch (_: Exception) {}
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

    override fun onRigRelayLine(line: String): Boolean = uploadRelay.onLine(line)

    override fun onRigConnected() {
        mainHandler.post {
            rigConnected = true
            uploadRelay.onConnected()
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
            else if (previewing) { rigLink.sendScanStart(); previewOwnsRigScan = true } // keep the rig feeding an open live tool across a reconnect
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
            uploadRelay.onDisconnected()
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
                val health = RigHealth(gps == 1, field("sats") ?: -1, field("sd") == 1, field("pend") ?: 0, System.currentTimeMillis(),
                    field("rw"), field("rb"),
                    field("heap"), field("sdfree"), field("wnode")?.let { it == 1 }, field("scan")?.let { it == 1 })
                mainHandler.post {
                    rigHealth = health
                    // The phone's clock, so the rig can timestamp uploads and run dock auto-upload
                    // even with no GPS time (garage, just rebooted).
                    if (rigConnected) rigLink.sendRaw("app time ${System.currentTimeMillis() / 1000}")
                    // Give the rig screen the same numbers every other surface shows.
                    if (running && rigConnected) rigLink.sendRaw("app counts $wifiCountThisRun $bleCountThisRun")
                }
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

    override fun onRigStateSnapshot(active: Boolean) {
        mainHandler.post {
            // Join a run the rig is already in (it resumed when the car started, say). Only ever
            // starts: if the phone is mid-run and the rig isn't, onRigConnected() already told the
            // rig to join the phone instead.
            if (active && !running && !previewing) startRun(notifyRig = false, reason = "joined the rig's run")
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
            // While previewing, the rig scanning is us feeding the live tools -
            // don't let it escalate into a full logged phone run.
            if (active && !running && !previewing) startRun(notifyRig = false, reason = "rig reported scanning")
            else if (!active && running) stopRun(notifyRig = false, reason = "rig reported stopped")
        }
    }

    companion object {
        private const val CHANNEL_ID = "wardrive_scan"
        private const val WATCH_CHANNEL_ID = "wardrive_watchlist"
        private const val ATTENTION_CHANNEL_ID = "wardrive_attention" // "tap to join" / "can't start" - must be seen
        private const val NOTIF_ID = 1
        private const val JOIN_NOTIF_ID = 2
        // BLE sightings are bursty - a stationary device isn't reported every
        // scan cycle (advertising duty-cycle, Android coalescing duplicate
        // adverts, the rig's per-run BLE dedup), so a short window flaps
        // left/entered on a device that never actually moved. Three minutes
        // rides out those gaps while still catching a genuine departure.
        private const val WATCH_LEAVE_MS = 180_000L  // no sighting for this long = the device left range
        private const val WATCH_SWEEP_MS = 15_000L   // how often we check the watchlist for departures
        private const val WATCH_NOTIFY_COOLDOWN_MS = 120_000L // min gap between notifications for one entry, so edge flapping can't spam
        // How far the current sighting has to be from where a flagged
        // tracker was last logged before it's treated as "traveling with
        // you" rather than "same spot as last time" - GPS/RSSI-position
        // noise alone can easily be tens of meters, so this stays well
        // above that.
        private const val PERSISTENT_TRACKER_ALERT_RADIUS_M = 500.0
        private const val GNSS_PERSIST_INTERVAL_MS = 15000L

        const val ACTION_START = "com.dreknil.wardrivebridge.START_RUN"
        const val ACTION_ASK_LOCATION = "com.dreknil.wardrivebridge.ASK_LOCATION"
        const val ACTION_STOP = "com.dreknil.wardrivebridge.STOP_RUN"
        private const val EXTERNAL_UI_INTERVAL_MS = 5_000L
        // Safety cap so a run that's never stopped can't hold the CPU awake forever; the idle
        // auto-stop normally ends a parked run long before this.
        private const val RUN_WAKELOCK_MAX_MS = 12 * 3600_000L
        private const val VALID_EPOCH_SEC = 1_600_000_000L // rx_time below this is the radio's uptime, not a date
        private const val FRESH_PACKET_SEC = 30L

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
            s.stopRun(notifyRig = true, reason = "tile/widget STOP")
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
