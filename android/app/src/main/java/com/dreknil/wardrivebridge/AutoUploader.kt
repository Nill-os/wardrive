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
            if (dao.observationsForRun(runId).isEmpty()) return@Thread
            val file = CsvExporter.materialize(app, dao, runId) ?: return@Thread
            UploadManager(app).upload(file, settings.wigleToken, settings.wdgwarsKey) { result ->
                val allOk = (!result.wdgwarsAttempted || result.wdgwarsOk) && (!result.wigleAttempted || result.wigleOk)
                if (allOk) dao.markUploaded(runId, System.currentTimeMillis())
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, if (allOk) "Run uploaded automatically" else "Auto-upload failed - retry from Logs", Toast.LENGTH_LONG).show()
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
