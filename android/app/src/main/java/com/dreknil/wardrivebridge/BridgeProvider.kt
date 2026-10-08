package com.dreknil.wardrivebridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets the Organic Maps build show this app's live wardrive status (its map overlay and the
 * Android Auto badge) and start/stop a run from the car - without running a second scanner of its
 * own. This app stays the only thing talking to the rig.
 *
 * query(content://com.dreknil.wardrivebridge.bridge/status) -> one row of status columns
 * (service_up = 0 when this app's scan service isn't running).
 * call("start" | "stop") -> starts/stops a run, same as the in-app button.
 *
 * Only the allowed caller gets an answer: package name AND signing certificate must both match -
 * the two apps are signed with different keys, so a signature permission can't express this.
 */
class BridgeProvider : ContentProvider() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        checkCaller()
        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(onMain { snapshot() })
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        val ok = onMain {
            val s = ScanService.instance ?: return@onMain false
            when (method) {
                "start" -> { if (!s.running) s.startRun(notifyRig = true, reason = "Organic Maps / car record button"); return@onMain s.running }
                "stop" -> if (s.running) s.stopRun(notifyRig = true, reason = "Organic Maps / car record button")
                else -> return@onMain false
            }
            true
        }
        return Bundle().apply { putBoolean("ok", ok) }
    }

    /** Main thread only - ScanService's state is only ever touched there. */
    private fun snapshot(): Array<Any> {
        val s = ScanService.instance ?: return arrayOf(0, 0, 0, 0, 0, 0, 0, "", 0, 0, 0)
        val wifi = s.wifiCountThisRun // same shared counts as the dashboard and the rig screen
        val ble = s.bleCountThisRun
        return arrayOf(
            1,
            if (s.running) 1 else 0,
            if (s.paused) 1 else 0,
            wifi, // WIGLE
            wifi + ble, // WDGW
            ble, // BT
            if (s.rigConnected) 1 else 0,
            s.rigLink.connectionType ?: "",
            s.meshLinkState.ordinal,
            s.excludedCount,
            if (AppSettings(s).rigCountFoundMode) 1 else 0, // count_mode: 0 = WIGLE/WDGW/BT, 1 = APS/BT/ALL
        )
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(2, TimeUnit.SECONDS)) throw IllegalStateException("main thread busy")
        return result!!.getOrThrow()
    }

    private fun checkCaller() {
        val pm = context!!.packageManager
        val packages = pm.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        val allowed = packages.any { pkg -> ALLOWED[pkg]?.let { certDigest(pm, pkg) == it } == true }
        if (!allowed) throw SecurityException("not allowed")
    }

    @Suppress("DEPRECATION")
    private fun certDigest(pm: PackageManager, pkg: String): String? {
        val sigs = try {
            if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val cert = sigs?.singleOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    companion object {
        val COLUMNS = arrayOf(
            "service_up", "running", "paused", "wigle", "wdgw", "bt",
            "cyd_connected", "cyd_transport", "rig_state", "excluded", "count_mode",
        )

        // Organic Maps debug build - package name -> SHA-256 of its signing certificate.
        private val ALLOWED = mapOf(
            "app.organicmaps.debug" to "96d6288178b01b869bd3ffbf95b33beede230168df882a1d7a4bb28b853459f4",
        )
    }
}
