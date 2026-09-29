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

    fun macBlacklist(): Set<String> =
        macBlacklistRaw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun ssidBlacklist(): Set<String> =
        ssidBlacklistRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
