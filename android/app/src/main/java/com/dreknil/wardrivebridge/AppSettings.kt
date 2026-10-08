package com.dreknil.wardrivebridge

import android.content.Context

/** SharedPreferences-backed settings: upload credentials and privacy
 * filters (home exclusion zone, MAC/SSID blacklist). Doubles stored as
 * strings since SharedPreferences has no native double type. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wardrive_settings", Context.MODE_PRIVATE)

    var wigleToken: String
        get() = prefs.getString("wigle_token", "") ?: ""
        set(value) = prefs.edit().putString("wigle_token", value).apply()

    var wdgwarsKey: String
        get() = prefs.getString("wdgwars_key", "") ?: ""
        set(value) = prefs.edit().putString("wdgwars_key", value).apply()

    var homeLat: Double
        get() = prefs.getString("home_lat", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("home_lat", value.toString()).apply()

    var homeLon: Double
        get() = prefs.getString("home_lon", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("home_lon", value.toString()).apply()

    var homeRadiusM: Double
        get() = prefs.getString("home_radius_m", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("home_radius_m", value.toString()).apply()

    var macBlacklistRaw: String
        get() = prefs.getString("mac_blacklist", "") ?: ""
        set(value) = prefs.edit().putString("mac_blacklist", value).apply()

    var ssidBlacklistRaw: String
        get() = prefs.getString("ssid_blacklist", "") ?: ""
        set(value) = prefs.edit().putString("ssid_blacklist", value).apply()

    // Watchlist: specific WiFi/BLE devices to be notified about when they come
    // into range or leave, each with its own enter/leave toggles. `key` matches a
    // MAC (if it contains ':') or an SSID/device-name substring; `label` is just
    // for display. Stored one entry per line as "key\tlabel\tenter\tleave".
    data class WatchEntry(val key: String, val label: String, val enter: Boolean, val leave: Boolean)

    var watchlistRaw: String
        get() = prefs.getString("watchlist", "") ?: ""
        set(value) = prefs.edit().putString("watchlist", value).apply()

    fun watchEntries(): List<WatchEntry> =
        watchlistRaw.split("\n").mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val p = t.split("\t")
            if (p.size >= 4) WatchEntry(p[0], p[1].ifBlank { p[0] }, p[2] == "1", p[3] == "1")
            else WatchEntry(t, t, true, true) // legacy plain line - default both toggles on
        }

    fun setWatchEntries(list: List<WatchEntry>) {
        watchlistRaw = list.joinToString("\n") {
            "${it.key.replace("\t", " ")}\t${it.label.replace("\t", " ")}\t${if (it.enter) "1" else "0"}\t${if (it.leave) "1" else "0"}"
        }
    }

    // 0 = keep forever, matching the firmware's own retentionDays convention.
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 0)
        set(value) = prefs.edit().putInt("retention_days", value).apply()

    // 0 = disabled (log regardless of GPS quality). HDOP above ~5 is
    // generally considered poor precision; a value like 8-10 catches
    // genuinely bad geometry (tunnels, urban canyons, no fix yet) without
    // being so strict it pauses logging during ordinary driving.
    var maxHdop: Double
        get() = prefs.getString("max_hdop", "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString("max_hdop", value.toString()).apply()

    /** Bluetooth address of the rig this phone is paired with; empty = not paired yet, in which
     *  case RigBleLink only looks for a rig whose owner has just opened a pairing window. */
    var pairedRigAddress: String
        get() = prefs.getString("paired_rig_address", "") ?: ""
        set(value) = prefs.edit().putString("paired_rig_address", value).apply()

    /** Read the user's own Meshtastic radio during runs (MeshtasticRadioLink) and upload its
     *  LoRa mesh nodes to WDGWars. The Meshtastic app can't be connected to the radio meanwhile. */
    var meshCollect: Boolean
        get() = prefs.getBoolean("mesh_collect", false)
        set(value) = prefs.edit().putBoolean("mesh_collect", value).apply()

    /** During runs, let the Meshtastic app use the radio through this app (MeshBridgeServer,
     *  127.0.0.1:4403) instead of over its own Bluetooth connection. */
    var meshBridge: Boolean
        get() = prefs.getBoolean("mesh_bridge", false)
        set(value) = prefs.edit().putBoolean("mesh_bridge", value).apply()

    /** Each radio's own node number as last confirmed by the radio ("address|nodeNum" lines), so
     *  it's excluded from the finds from the first second of a run (see ScanService.meshOwnNums). */
    private var meshOwnNodes: String
        get() = prefs.getString("mesh_own_node", "") ?: ""
        set(value) = prefs.edit().putString("mesh_own_node", value).apply()

    fun meshOwnNodeFor(address: String): Long? = meshOwnNodes.lineSequence()
        .map { it.split("|") }.firstOrNull { it.size == 2 && it[0] == address }?.get(1)?.toLongOrNull()

    /** Every radio's confirmed own node number - all of them are the user's, never finds. */
    fun meshOwnNodeNums(): List<Long> = meshOwnNodes.lineSequence()
        .mapNotNull { it.split("|").takeIf { p -> p.size == 2 }?.get(1)?.toLongOrNull() }.toList()

    fun setMeshOwnNode(address: String, nodeNum: Long) {
        if (address.isEmpty()) return
        if (meshOwnNodeFor(address) == nodeNum && meshOwnNodes.lines().lastOrNull { it.isNotBlank() } == "$address|$nodeNum") return
        val others = meshOwnNodes.lines().filter { it.isNotBlank() && !it.startsWith("$address|") }.takeLast(63) // effectively unbounded - a forgotten own radio would be logged as a find
        meshOwnNodes = (others + "$address|$nodeNum").joinToString("\n")
    }

    /** Bluetooth address and name of that radio (picked from the phone's paired devices). */
    var meshRadioAddress: String
        get() = prefs.getString("mesh_radio_address", "") ?: ""
        set(value) = prefs.edit().putString("mesh_radio_address", value).apply()
    var meshRadioName: String
        get() = prefs.getString("mesh_radio_name", "") ?: ""
        set(value) = prefs.edit().putString("mesh_radio_name", value).apply()

    /** Minutes between spoken run updates while driving; 0 = off. */
    var spokenUpdateMinutes: Int
        get() = prefs.getInt("spoken_update_minutes", 0)
        set(value) = prefs.edit().putInt("spoken_update_minutes", value).apply()

    /** Say it out loud when a tracker seems to be following you. */
    var spokenTrackerAlerts: Boolean
        get() = prefs.getBoolean("spoken_tracker_alerts", true)
        set(value) = prefs.edit().putBoolean("spoken_tracker_alerts", value).apply()

    // Per-category "heads up, X nearby" alerts (spoken + vibrate) the first
    // time each device is seen in a run. Off by default - opt in per type.
    private fun alertPref(key: String) = prefs.getBoolean(key, false)
    private fun setAlertPref(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    var alertFlock: Boolean get() = alertPref("alert_flock"); set(v) = setAlertPref("alert_flock", v)
    var alertPolice: Boolean get() = alertPref("alert_police"); set(v) = setAlertPref("alert_police", v)
    var alertSkimmer: Boolean get() = alertPref("alert_skimmer"); set(v) = setAlertPref("alert_skimmer", v)
    var alertFlipper: Boolean get() = alertPref("alert_flipper"); set(v) = setAlertPref("alert_flipper", v)
    var alertDrone: Boolean get() = alertPref("alert_drone"); set(v) = setAlertPref("alert_drone", v)
    var alertMesh: Boolean get() = alertPref("alert_mesh"); set(v) = setAlertPref("alert_mesh", v)
    var alertGlasses: Boolean get() = alertPref("alert_glasses"); set(v) = setAlertPref("alert_glasses", v)
    var alertActionCam: Boolean get() = alertPref("alert_actioncam"); set(v) = setAlertPref("alert_actioncam", v)
    var alertPineapple: Boolean get() = alertPref("alert_pineapple"); set(v) = setAlertPref("alert_pineapple", v)

    /** Upload each run as soon as it stops, if the phone is on WiFi. */
    var autoUploadOnWifi: Boolean
        get() = prefs.getBoolean("auto_upload_wifi", false)
        set(value) = prefs.edit().putBoolean("auto_upload_wifi", value).apply()

    /** Trip mode: while on, the rig's log files upload through THIS phone's internet over
     *  Bluetooth (no rig WiFi needed) and the rig holds off its own upload. Off by default - the
     *  rig then uploads over its own WiFi as usual. Turn it on for a trip away from home WiFi. */
    var tripMode: Boolean
        get() = prefs.getBoolean("trip_mode", false)
        set(value) = prefs.edit().putBoolean("trip_mode", value).apply()

    /** App UI accent colours (ARGB Int). Default to the built-in cyan / purple. */
    var accentPrimary: Int
        get() = prefs.getInt("accent_primary", 0xFF00F0FF.toInt())
        set(value) = prefs.edit().putInt("accent_primary", value).apply()

    var accentSecondary: Int
        get() = prefs.getInt("accent_secondary", 0xFF9D00FF.toInt())
        set(value) = prefs.edit().putInt("accent_secondary", value).apply()

    // Rig LED colours (RRGGBB, no alpha) and screen settings, mirrored to the rig over Bluetooth.
    var rigLedAp: Int get() = prefs.getInt("rig_led_ap", 0xFF00FF); set(v) = prefs.edit().putInt("rig_led_ap", v).apply()
    var rigLedBle: Int get() = prefs.getInt("rig_led_ble", 0x00FFFF); set(v) = prefs.edit().putInt("rig_led_ble", v).apply()
    var rigLedOk: Int get() = prefs.getInt("rig_led_ok", 0x00FF00); set(v) = prefs.edit().putInt("rig_led_ok", v).apply()
    var rigLedFail: Int get() = prefs.getInt("rig_led_fail", 0xFF0000); set(v) = prefs.edit().putInt("rig_led_fail", v).apply()
    var rigLedBrightness: Int get() = prefs.getInt("rig_led_brightness", 3); set(v) = prefs.edit().putInt("rig_led_brightness", v).apply()
    var rigScreenBrightness: Int get() = prefs.getInt("rig_screen_brightness", 100); set(v) = prefs.edit().putInt("rig_screen_brightness", v).apply()
    var rigScreenTimeoutSec: Int get() = prefs.getInt("rig_screen_timeout", 0); set(v) = prefs.edit().putInt("rig_screen_timeout", v).apply()
    var rigScreenKeepOnScanning: Boolean get() = prefs.getBoolean("rig_screen_keepon", true); set(v) = prefs.edit().putBoolean("rig_screen_keepon", v).apply()
    // false = rig shows WiGLE/WDGW counters (default), true = raw found APs / Bluetooth
    var rigCountFoundMode: Boolean get() = prefs.getBoolean("rig_count_found", false); set(v) = prefs.edit().putBoolean("rig_count_found", v).apply()

    fun macBlacklist(): Set<String> =
        macBlacklistRaw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun ssidBlacklist(): Set<String> =
        ssidBlacklistRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
