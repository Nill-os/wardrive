package com.dreknil.wardrivebridge

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Uploads a run's Meshtastic nodes to WDGWars. Mesh nodes can't ride in the WiGLE CSV: WDGWars
 * takes them in its JSON upload (POST /api/upload), in a "meshcore_nodes" array of
 * {node_id, lat, lon, network = "meshtastic"}, signed with HMAC-SHA256 over nonce + base64(data)
 * keyed with the API key (wdgwars.pl developer docs, "Method 2: JSON Upload" and "Mesh nodes").
 * A node counts once, worldwide, for whoever logs it first, and only with a position: its own
 * broadcast position, else where this phone heard it. Nodes the radio only knows through MQTT
 * (internet, not heard over the air) are left out. Call off the main thread.
 */
object MeshUploader {
    data class Result(val attempted: Boolean, val ok: Boolean, val message: String)

    fun uploadRun(context: Context, runId: Long, wdgwarsKey: String): Result {
        if (wdgwarsKey.isBlank()) return Result(false, false, "skipped (no WDGWars key)")
        val nodes = AppDatabase.get(context).dao().meshNodesForRun(runId)
        val settings = AppSettings(context)
        val items = JSONArray()
        for (n in nodes) {
            if (n.viaMqtt) continue
            if (n.longName.endsWith("_nomap", true) || n.longName.endsWith("_optout", true)) continue
            val (lat, lon) = when {
                n.lat != 0.0 || n.lon != 0.0 -> n.lat to n.lon
                n.heardLat != 0.0 || n.heardLon != 0.0 -> n.heardLat to n.heardLon
                else -> continue // WDGWars rejects a node with no position ("no_gps")
            }
            if (insideHomeZone(settings, lat, lon)) continue // same privacy zone as the WiFi/BLE log
            items.put(JSONObject().apply {
                put("node_id", n.nodeId)
                put("lat", lat)
                put("lon", lon)
                put("network", "meshtastic")
                if (n.longName.isNotBlank()) put("name", n.longName)
            })
        }
        if (items.length() == 0) return Result(false, false, if (nodes.isEmpty()) "no mesh nodes this run" else "no mesh node had a position")
        val payload = JSONObject().put("meshcore_nodes", items).toString()
        val (ok, msg) = post(wdgwarsKey, payload)
        return Result(true, ok, summarize(msg) ?: msg)
    }

    private fun insideHomeZone(s: AppSettings, lat: Double, lon: Double): Boolean {
        if (s.homeRadiusM <= 0 || (s.homeLat == 0.0 && s.homeLon == 0.0)) return false
        val d = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, s.homeLat, s.homeLon, d)
        return d[0] <= s.homeRadiusM
    }

    /** "12 new, 3 rejected (no_gps 2, bad_node_id 1)" from the upload answer, or null. */
    fun summarize(message: String): String? {
        val imported = Regex("\"meshcore_imported\"\\s*:\\s*(\\d+)").find(message)?.groupValues?.get(1) ?: return null
        val reasons = Regex("\"meshcore_reject_reasons\"\\s*:\\s*\\{([^}]*)\\}").find(message)?.groupValues?.get(1)
            ?.let { Regex("\"(\\w+)\"\\s*:\\s*(\\d+)").findAll(it).map { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }.toList() }
            .orEmpty()
        return "mesh: $imported new" + if (reasons.isEmpty()) "" else ", rejected (${reasons.joinToString(", ")})"
    }

    private fun post(apiKey: String, payloadJson: String): Pair<Boolean, String> {
        val dataB64 = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val sig = mac.doFinal((nonce + dataB64).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("data", dataB64).put("nonce", nonce).put("sig", sig).toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("https://wdgwars.pl/api/upload").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-API-Key", apiKey)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }?.take(800) ?: ""
            val ok = code in 200..299 && !Regex("\"(ok|success)\"\\s*:\\s*false").containsMatchIn(text)
            ok to "HTTP $code: $text"
        } catch (e: Exception) {
            val c = conn
            val code = try { c?.responseCode ?: -1 } catch (_: Exception) { -1 }
            val text = try { c?.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) } catch (_: Exception) { null } ?: e.message ?: ""
            false to "HTTP $code: $text"
        } finally {
            conn?.disconnect()
        }
    }
}
