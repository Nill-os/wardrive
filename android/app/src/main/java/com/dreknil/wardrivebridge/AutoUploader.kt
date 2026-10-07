package com.dreknil.wardrivebridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Uploads a run the moment it stops, when Settings says so and the phone is on WiFi (an
 *  unmetered network) - so a drive home that ends in the driveway uploads by itself. Same rule
 *  as the manual button: a run only counts as uploaded once every configured service took it. */
object AutoUploader {
    fun maybeUpload(context: Context, runId: Long) {
        val settings = AppSettings(context)
        if (!settings.autoUploadOnWifi) return
        if (settings.wigleToken.isBlank() && settings.wdgwarsKey.isBlank()) return
        if (!onUnmeteredNetwork(context)) return

        val app = context.applicationContext
        Thread {
            val dao = AppDatabase.get(app).dao()
            // stopRun() deletes a run that never logged anything, on its own executor - skip those.
            val observations = dao.observationCount(runId)
            if (observations == 0 && dao.meshNodeCount(runId) == 0) return@Thread
            if (observations == 0) { // mesh nodes only - nothing for the CSV
                val mesh = MeshUploader.uploadRun(app, runId, settings.wdgwarsKey)
                if (mesh.attempted && mesh.ok) dao.markUploaded(runId, System.currentTimeMillis())
                if (mesh.attempted) Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, if (mesh.ok) "Run uploaded automatically (${mesh.message})" else "Auto-upload failed - retry from Logs (${mesh.message})", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val file = CsvExporter.materialize(app, dao, runId) ?: return@Thread
            UploadManager(app).upload(file, settings.wigleToken, settings.wdgwarsKey) { result ->
                val mesh = MeshUploader.uploadRun(app, runId, settings.wdgwarsKey) // mesh nodes: WDGWars JSON upload
                val allOk = (!result.wdgwarsAttempted || result.wdgwarsOk) && (!result.wigleAttempted || result.wigleOk) &&
                    (!mesh.attempted || mesh.ok)
                if (allOk) dao.markUploaded(runId, System.currentTimeMillis())
                Handler(Looper.getMainLooper()).post {
                    val meshNote = if (mesh.attempted) " (${mesh.message})" else ""
                    Toast.makeText(app, if (allOk) "Run uploaded automatically$meshNote" else "Auto-upload failed - retry from Logs$meshNote", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
