package com.dreknil.wardrivebridge

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Direct upload of a WigleWifi-1.6 CSV to wdgwars.pl and (optionally) WiGLE.
 * Mirrors the ESP32 rig's own Uploader.cpp exactly (same endpoints, same
 * header/field conventions) so a file from either source uploads the same
 * way. wdgwars.pl's auth header/field name are the same "most common
 * convention" guess the firmware makes - confirm against your account's
 * docs at wdgwars.pl if a key gets rejected.
 */
class UploadManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val boundary = "----WardriveBridgeBoundary9f3c2a"

    data class Result(val wdgwarsAttempted: Boolean, val wdgwarsOk: Boolean, val wdgwarsMessage: String, val wigleAttempted: Boolean, val wigleOk: Boolean, val wigleMessage: String)

    fun upload(file: File, wigleToken: String, wdgwarsKey: String, callback: (Result) -> Unit) {
        executor.execute {
            val body = buildMultipartBody(file)

            var wdgwarsAttempted = false
            var wdgOk = false
            var wdgMsg = "skipped (no key set)"
            if (wdgwarsKey.isNotBlank()) {
                wdgwarsAttempted = true
                val (ok, msg) = post("https://wdgwars.pl/api/upload-csv", body, mapOf("X-Api-Key" to wdgwarsKey))
                wdgOk = ok
                wdgMsg = msg
            }

            var wigleAttempted = false
            var wigleOk = false
            var wigleMsg = "skipped (no token set)"
            if (wigleToken.isNotBlank()) {
                wigleAttempted = true
                val (ok, msg) = post("https://api.wigle.net/api/v2/file/upload", body, mapOf("Authorization" to "Basic $wigleToken"))
                wigleOk = ok
                wigleMsg = msg
            }

            callback(Result(wdgwarsAttempted, wdgOk, wdgMsg, wigleAttempted, wigleOk, wigleMsg))
        }
    }

    private fun buildMultipartBody(file: File): ByteArray {
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: text/csv\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val out = java.io.ByteArrayOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.write(tail.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    // 200/201/202/409 all count as success, matching the rig's own
    // convention (409 = wdgwars.pl's server-side dedup, not a real failure).
    private fun isSuccessCode(code: Int): Boolean = code == 200 || code == 201 || code == 202 || code == 409

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): Pair<Boolean, String> {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }

            val code = c.responseCode
            val ok = isSuccessCode(code)
            val stream = if (ok) c.inputStream else c.errorStream
            val respText = stream?.bufferedReader()?.use { it.readText() }?.take(300) ?: ""
            ok to "HTTP $code${if (respText.isNotBlank()) ": $respText" else ""}"
        } catch (e: java.io.FileNotFoundException) {
            // Android's OkHttp-backed HttpURLConnection throws FileNotFoundException straight out
            // of getResponseCode()/getInputStream() for any non-2xx response, instead of just
            // returning the status - so a genuine server error (rate limit, bad key, server
            // down) used to get reported as the useless "error: https://wdgwars.pl/api/upload-csv"
            // instead of the real status and message. The HTTP exchange itself already completed
            // by the time this throws, so the status and error body are still readable from the
            // same connection - recover them here instead of losing them. Confirmed for real: a
            // rejected wdgwars.pl upload reported exactly this unhelpful message (2026-09-28).
            val c = conn
            if (c != null) {
                val code = try { c.responseCode } catch (_: Exception) { -1 }
                val ok = isSuccessCode(code)
                val respText = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                } catch (_: Exception) {
                    null
                } ?: ""
                ok to "HTTP $code${if (respText.isNotBlank()) ": $respText" else ""}"
            } else {
                false to "error: ${e.message}"
            }
        } catch (e: Exception) {
            false to "error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }
}
