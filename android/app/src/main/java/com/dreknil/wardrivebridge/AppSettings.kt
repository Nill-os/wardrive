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

    /** Minutes between spoken run updates while driving; 0 = off. */
    var spokenUpdateMinutes: Int
        get() = prefs.getInt("spoken_update_minutes", 0)
        set(value) = prefs.edit().putInt("spoken_update_minutes", value).apply()

    /** Say it out loud when a tracker seems to be following you. */
    var spokenTrackerAlerts: Boolean
        get() = prefs.getBoolean("spoken_tracker_alerts", true)
        set(value) = prefs.edit().putBoolean("spoken_tracker_alerts", value).apply()

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

    fun macBlacklist(): Set<String> =
        macBlacklistRaw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun ssidBlacklist(): Set<String> =
        ssidBlacklistRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
