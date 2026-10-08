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
    /** nothingToSend: a key is set but the run has no node WDGWars could take (none, or none
     *  with a position / outside the home zone), so the run needs no upload. */
    data class Result(val attempted: Boolean, val ok: Boolean, val message: String, val nothingToSend: Boolean = false)

    fun uploadRun(context: Context, runId: Long, wdgwarsKey: String): Result {
        if (wdgwarsKey.isBlank()) return Result(false, false, "skipped (no WDGWars key)")
        val nodes = AppDatabase.get(context).dao().meshNodesForRun(runId)
        val settings = AppSettings(context)
        val items = JSONArray()
        val ownNums = settings.meshOwnNodeNums().toSet() // never upload the user's own radios (also purged from the DB)
        for (n in nodes) {
            if (n.viaMqtt || n.nodeNum in ownNums || n.nodeNum == 0L || n.nodeNum == 0xffffffffL) continue
            if (n.longName.endsWith("_nomap", true) || n.longName.endsWith("_optout", true)) continue
            val (lat, lon) = when {
                n.lat != 0.0 || n.lon != 0.0 -> n.lat to n.lon
                n.heardLat != 0.0 || n.heardLon != 0.0 -> n.heardLat to n.heardLon
                else -> continue // WDGWars rejects a node with no position ("no_gps")
            }
            if (insideHomeZone(settings, lat, lon)) continue // same privacy zone as the WiFi/BLE log
            items.put(JSONObject().apply {
                // From the node number, never the stored nodeId: rows from earlier builds hold the
                // id the node broadcast about itself, which any node can set to anything.
                put("node_id", "!%08x".format(n.nodeNum))
                put("lat", lat)
                put("lon", lon)
                put("network", "meshtastic")
                if (n.longName.isNotBlank()) put("name", n.longName)
            })
        }
        if (items.length() == 0) return Result(false, false,
            if (nodes.isEmpty()) "no mesh nodes this run" else "no mesh node to send (none with a position outside the home zone)", nothingToSend = true)
        // The server answers "Invalid data format" unless the payload has its "networks" list,
        // even an empty one (checked against the live API 2026-10-07).
        val payload = JSONObject().put("networks", JSONArray()).put("meshcore_nodes", items).toString()
        val (ok, msg) = post(context, wdgwarsKey, payload)
        // Success = a 2xx whose JSON isn't an error ("ok": false / an "error" key) - a 200 carrying
        // just an error must not mark the run uploaded. meshcore_imported is optional in the answer.
        val body = msg.substringAfter(": ", "").trim()
        val accepted = ok && (msg.startsWith("HTTP 409") || body.isEmpty() || try {
            val j = JSONObject(body)
            val err = j.opt("error")
            val hasError = err != null && err != JSONObject.NULL && err != false && err.toString().isNotBlank()
            !hasError && j.optBoolean("ok", true) && j.optBoolean("success", true)
        } catch (_: Exception) { summarize(body) != null }) // not parseable whole (e.g. cut off) - trust an import count
        return Result(true, accepted, summarize(msg) ?: msg.take(300))
    }

    private fun insideHomeZone(s: AppSettings, lat: Double, lon: Double): Boolean {
        if (s.homeRadiusM <= 0 || (s.homeLat == 0.0 && s.homeLon == 0.0)) return false
        val d = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, s.homeLat, s.homeLon, d)
        return d[0] <= s.homeRadiusM
    }

    /** "mesh: 12 new, 5 already logged, rejected (no_gps 2)" from the upload answer, or null. */
    fun summarize(message: String): String? {
        fun num(key: String) = Regex("\"$key\"\\s*:\\s*(\\d+)").find(message)?.groupValues?.get(1)?.toInt()
        val imported = num("meshcore_imported") ?: return null
        val known = (num("meshcore_already_seen") ?: 0) + (num("meshcore_yours_known") ?: 0) + (num("meshcore_owned_by_others") ?: 0)
        val reasons = Regex("\"meshcore_reject_reasons\"\\s*:\\s*\\{([^}]*)\\}").find(message)?.groupValues?.get(1)
            ?.let { Regex("\"(\\w+)\"\\s*:\\s*(\\d+)").findAll(it).filter { m -> m.groupValues[2] != "0" }.map { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }.toList() }
            .orEmpty()
        return "mesh: $imported new" + (if (known > 0) ", $known already logged" else "") +
            if (reasons.isEmpty()) "" else ", rejected (${reasons.joinToString(", ")})"
    }

    // Debug builds can send uploads to a bench host instead (same file UploadManager uses), so a
    // test run's mesh nodes don't land on the real account.
    private fun debugHost(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        return try {
            val f = java.io.File(context.applicationContext.filesDir, "debug_upload_host.txt")
            if (f.exists()) f.readText().trim().ifBlank { null } else null
        } catch (_: Exception) { null }
    }

    private fun post(context: Context, apiKey: String, payloadJson: String): Pair<Boolean, String> {
        val dataB64 = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val sig = mac.doFinal((nonce + dataB64).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("data", dataB64).put("nonce", nonce).put("sig", sig).toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(debugHost(context) ?: "https://wdgwars.pl/api/upload").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-API-Key", apiKey)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }?.take(1_000_000) ?: ""
            // 409 = the server already has this payload (its dedup) - as UploadManager treats the CSV.
            val ok = (code in 200..299 && !Regex("\"(ok|success)\"\\s*:\\s*false").containsMatchIn(text)) || code == 409
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
