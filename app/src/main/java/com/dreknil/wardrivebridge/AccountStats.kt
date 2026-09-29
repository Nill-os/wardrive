package com.dreknil.wardrivebridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only account totals from wdgwars.pl and WiGLE - the "how am I doing
 * overall" numbers, distinct from this run's live counts. Both are simple
 * GETs with the same credentials already used for upload (UploadManager),
 * no new setup needed. Field names for both were confirmed live (2026-09-27)
 * against a real account rather than guessed from docs.
 */
object AccountStats {

    data class WdgwarsDevice(val name: String, val networks: Int, val uploads: Int, val lastUpload: String)
    // A gang-territory claim event, NOT a WPA handshake/PMKID capture - see
    // fetchWdgwars()'s own comment. apCount is how many APs backed the
    // claim; defenderGang is whichever gang (if any) held that territory
    // before this claim.
    data class TerritoryCapture(val whenIso: String, val apCount: Int, val lat: Double, val lon: Double, val defenderGang: String?)
    data class WdgwarsTotals(
        val total: Int, val wifi: Int, val ble: Int,
        val recent7d: Int,
        val gang: String?, val gangRole: String?,
        val creditsBalance: Int,
        val badgeCount: Int,
        val newApLimitUsed: Int, val newApLimitCap: Int,
        val devices: List<WdgwarsDevice>,
        val recentCaptures: List<TerritoryCapture>,
    )

    data class WigleTotals(
        val discoveredWifi: Int, val discoveredBt: Int, val discoveredCell: Int,
        val rank: Int, val monthRank: Int,
        val eventMonthCount: Int,
        val totalWiFiLocations: Int,
    )

    // wdgwars.pl publishes no formal API docs - this endpoint and field
    // names come from reverse-engineering (community tools like
    // wdgwars-upload-check use the same GET /endpoint/me contract), verified
    // live against a real account 2026-09-27. "total" is wifi+ble lifetime
    // unique discoveries, which is what the site calls your score/points.
    // "devices" is one entry per uploading client this account has ever
    // used (this rig's ESP32 shows up as "ESP") with its own networks/
    // uploads/last_upload - the real per-platform upload history the
    // Analytics screen's Platforms section surfaces, not a local guess.
    // "recent_captures" is wdgwars' own gang-territory game mechanic (an AP
    // count claiming nearby territory from a rival gang) - unrelated to
    // WPA handshake/PMKID capture, which this app has no way to do at all.
    fun fetchWdgwars(apiKey: String): WdgwarsTotals? {
        if (apiKey.isBlank()) return null
        val json = getJson("https://wdgwars.pl/endpoint/me", mapOf("X-Api-Key" to apiKey)) ?: return null
        return try {
            val devicesJson = json.optJSONArray("devices")
            val devices = mutableListOf<WdgwarsDevice>()
            if (devicesJson != null) {
                for (i in 0 until devicesJson.length()) {
                    val d = devicesJson.optJSONObject(i) ?: continue
                    devices.add(
                        WdgwarsDevice(
                            name = d.optString("device_name", "?"),
                            networks = d.optInt("networks"),
                            uploads = d.optInt("uploads"),
                            lastUpload = d.optString("last_upload", ""),
                        )
                    )
                }
            }
            val limit = json.optJSONObject("new_ap_limit")
            val capturesJson = json.optJSONArray("recent_captures")
            val captures = mutableListOf<TerritoryCapture>()
            if (capturesJson != null) {
                for (i in 0 until capturesJson.length()) {
                    val c = capturesJson.optJSONObject(i) ?: continue
                    captures.add(
                        TerritoryCapture(
                            whenIso = c.optString("when", ""),
                            apCount = c.optInt("ap_count"),
                            lat = c.optDouble("lat", 0.0),
                            lon = c.optDouble("lng", 0.0),
                            defenderGang = if (c.isNull("defender_gang")) null else c.optString("defender_gang"),
                        )
                    )
                }
            }
            WdgwarsTotals(
                total = json.optInt("total"),
                wifi = json.optInt("wifi"),
                ble = json.optInt("ble"),
                recent7d = json.optInt("recent_7d"),
                gang = if (json.isNull("gang")) null else json.optString("gang"),
                gangRole = if (json.isNull("gang_role")) null else json.optString("gang_role"),
                creditsBalance = json.optJSONObject("credits")?.optInt("balance") ?: 0,
                badgeCount = json.optJSONArray("badges")?.length() ?: 0,
                newApLimitUsed = limit?.optInt("used") ?: 0,
                newApLimitCap = limit?.optInt("cap") ?: 0,
                devices = devices,
                recentCaptures = captures,
            )
        } catch (e: Exception) {
            null
        }
    }

    // Official documented endpoint (api.wigle.net/swagger -> GET
    // /api/v2/stats/user). discoveredWiFi/discoveredBt/discoveredCell are
    // WiGLE's own lifetime counts of first-ever sightings credited to this
    // account ("new found" in their own terms), not something computed
    // locally - confirmed live 2026-09-27 (field names differ slightly from
    // the swagger examples floating around online, e.g. "discoveredBt" not
    // "discoveredBT").
    fun fetchWigle(basicAuthToken: String): WigleTotals? {
        if (basicAuthToken.isBlank()) return null
        val json = getJson("https://api.wigle.net/api/v2/stats/user", mapOf("Authorization" to "Basic $basicAuthToken")) ?: return null
        return try {
            if (!json.optBoolean("success", false)) return null
            val stats = json.optJSONObject("statistics") ?: return null
            WigleTotals(
                discoveredWifi = stats.optInt("discoveredWiFi"),
                discoveredBt = stats.optInt("discoveredBt"),
                discoveredCell = stats.optInt("discoveredCell"),
                rank = stats.optInt("rank"),
                monthRank = stats.optInt("monthRank"),
                eventMonthCount = stats.optInt("eventMonthCount"),
                totalWiFiLocations = stats.optInt("totalWiFiLocations"),
            )
        } catch (e: Exception) {
            null
        }
    }

    // Same class of thing UploadManager.post() had: Android's OkHttp-backed HttpURLConnection
    // throws FileNotFoundException straight out of getResponseCode() for any non-2xx response,
    // so a rejected/rate-limited/expired-token request already ends up here in the catch block
    // rather than the explicit "!in 200..299" check below ever running. The end result (null) is
    // the same either way - nothing downstream reads an error message from this function, unlike
    // the upload flow's Toast which needed the real status text - so this only logs the reason
    // instead of restructuring the return type, to make a "why are stats blank" report
    // diagnosable via logcat without changing any caller-visible behavior.
    private fun getJson(url: String, headers: Map<String, String>): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "GET"
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) {
                android.util.Log.d("AccountStats", "getJson($url) -> HTTP ${c.responseCode}")
                return null
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            val code = try { conn?.responseCode } catch (_: Exception) { null }
            android.util.Log.d("AccountStats", "getJson($url) -> HTTP $code (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
