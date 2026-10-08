package com.dreknil.wardrivebridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Uploads the rig's own log files through the phone's internet, over the existing Bluetooth link.
 *
 * The rig can't reach the internet on its own unless it joins WiFi, and its auto-upload needs a
 * GPS fix to know it's home. This sidesteps both: while a phone is connected, it pulls each
 * pending file off the rig line by line (WD:PEND / WD:FBEGIN / WD:FROW / WD:FEND), uploads it to
 * WiGLE and wdgwars with the user's keys over whatever connection the phone has (cell or WiFi),
 * then tells the rig which files succeeded (`rig ack <name>`) so the rig marks them done and never
 * re-uploads. The rig also holds off its own WiFi upload while this is running, so nothing is
 * uploaded twice.
 *
 * Driven by the lines RigLinkManager already parses; call [onLine] for each WD: line and [onIdle]
 * periodically. All state is touched on the main thread.
 */
class RigUploadRelay(
    private val context: Context,
    private val settings: AppSettings,
    private val send: (String) -> Unit,
    private val log: (String) -> Unit,
    private val onProgress: (uploaded: Int, failed: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, "rig_relay").apply { mkdirs() }

    private enum class State { IDLE, LISTING, RECEIVING, UPLOADING }
    private var state = State.IDLE
    private val pending = ArrayDeque<String>()        // file names still to fetch
    private var current: String? = null               // file being received
    private var currentFile: File? = null
    private var currentWriter: java.io.BufferedWriter? = null
    private var rowsWritten = 0
    private var lastActivityMs = 0L
    private var uploadedThisRun = 0
    private var failedThisRun = 0
    private var connected = false
    private var currentRetries = 0
    private val retryCounts = HashMap<String, Int>()
    // The user's own devices and blacklisted MACs (uppercase), refreshed for each file received.
    private var excludedMacs: Set<String> = emptySet()

    fun onConnected() {
        connected = true
        // Ask what's waiting shortly after connect - the rig auto-starts its stream first.
        handler.postDelayed({ if (connected) requestList() }, 3_000)
    }

    fun onDisconnected() {
        connected = false
        abortToIdle()
    }

    /** Feed every WD: line here (from RigLinkManager). Returns true if the line was ours. */
    fun onLine(line: String): Boolean {
        when {
            line.startsWith("WD:PEND name=") -> {
                val name = field(line, "name")
                if (name != null && safeName(name)) pending.addLast(name)
                lastActivityMs = System.currentTimeMillis()
                return true
            }
            line == "WD:PENDEND" -> {
                state = State.IDLE
                fetchNext()
                return true
            }
            line.startsWith("WD:FBEGIN ") -> {
                startReceiving(field(line, "name"))
                return true
            }
            line.startsWith("WD:FROW ") -> {
                if (state == State.RECEIVING) {
                    val row = line.substring(8)
                    // The rig logs every advertiser, the user's own Meshtastic radio and the rig itself
                    // included, and it has no blacklist of its own: drop those rows before upload.
                    if (row.substringBefore(',').trim().uppercase() in excludedMacs) { lastActivityMs = System.currentTimeMillis(); return true }
                    currentWriter?.write(row)
                    currentWriter?.write("\n")
                    rowsWritten++
                    lastActivityMs = System.currentTimeMillis()
                }
                return true
            }
            line.startsWith("WD:FEND ") -> {
                val expected = field(line, "rows")?.toIntOrNull()
                finishReceiving(expected)
                return true
            }
            line.startsWith("WD:FERR ") -> {
                log("[relay] rig couldn't send ${current ?: ""}: ${line.substring(8)}")
                dropCurrent()
                fetchNext()
                return true
            }
        }
        return false
    }

    /** Call periodically; re-checks the list and times out a stalled transfer. */
    fun onIdle() {
        if (!connected) return
        if (state != State.IDLE && System.currentTimeMillis() - lastActivityMs > STALL_MS) {
            log("[relay] transfer stalled - resetting")
            abortToIdle()
        }
        if (state == State.IDLE && pending.isEmpty() &&
            System.currentTimeMillis() - lastActivityMs > RELIST_MS) {
            requestList()
        }
    }

    private fun requestList() {
        if (!enabled()) return
        if (state != State.IDLE) return
        uploadedThisRun = 0
        failedThisRun = 0
        state = State.LISTING
        lastActivityMs = System.currentTimeMillis()
        // Tell the rig we're handling uploads, then ask for the list.
        send("rig autoupload off")
        send("rig files")
    }

    private fun fetchNext() {
        if (!connected) return
        val next = pending.removeFirstOrNull()
        if (next == null) {
            if (uploadedThisRun > 0 || failedThisRun > 0) onProgress(uploadedThisRun, failedThisRun)
            state = State.IDLE
            lastActivityMs = System.currentTimeMillis()
            return
        }
        current = next
        send("rig send $next")
        lastActivityMs = System.currentTimeMillis()
    }

    private fun startReceiving(name: String?) {
        excludedMacs = settings.meshOwnRadioAddresses() + settings.macBlacklist() +
            setOfNotNull(settings.pairedRigAddress.uppercase().takeIf { it.isNotBlank() })
        if (name == null || !safeName(name)) { dropCurrent(); fetchNext(); return }
        current = name
        val f = File(dir, name)
        try {
            currentWriter = f.bufferedWriter()
            currentFile = f
            rowsWritten = 0
            state = State.RECEIVING
            lastActivityMs = System.currentTimeMillis()
        } catch (e: Exception) {
            log("[relay] can't buffer $name: ${e.message}")
            dropCurrent(); fetchNext()
        }
    }

    private fun finishReceiving(expectedRows: Int?) {
        val f = currentFile
        try { currentWriter?.flush(); currentWriter?.close() } catch (_: Exception) {}
        currentWriter = null
        if (f == null || rowsWritten == 0) { dropCurrent(); fetchNext(); return }
        // BLE notify can drop packets; the rig tells us how many rows it sent. If we got fewer,
        // the file is incomplete - re-request it (bounded) rather than upload a truncated CSV.
        val name = current ?: f.name
        if (expectedRows != null && rowsWritten != expectedRows) {
            val tries = (retryCounts[name] ?: 0) + 1
            retryCounts[name] = tries
            log("[relay] $name incomplete ($rowsWritten/$expectedRows rows) - ${if (tries <= MAX_RETRIES) "retrying" else "giving up"}")
            dropCurrent()
            if (tries <= MAX_RETRIES) { pending.addFirst(name) }
            state = State.IDLE
            lastActivityMs = System.currentTimeMillis()
            fetchNext()
            return
        }
        state = State.UPLOADING
        log("[relay] uploading $name ($rowsWritten rows) via phone")
        UploadManager(context).upload(f, settings.wigleToken, settings.wdgwarsKey) { result ->
            val allOk = (!result.wdgwarsAttempted || result.wdgwarsOk) && (!result.wigleAttempted || result.wigleOk)
            handler.post {
                if (allOk) {
                    // Include the phone's wall-clock epoch (seconds) so the rig can
                    // reset its "last upload" age even with no GPS time of its own -
                    // otherwise the CYD kept showing the age since its last self-upload
                    // (e.g. "12d ago") after every phone upload. Rig tolerates the
                    // epoch being absent (older firmware).
                    send("rig ack $name ${System.currentTimeMillis() / 1000}")  // rig marks it .uploaded, won't resend

                    uploadedThisRun++
                    log("[relay] $name uploaded")
                } else {
                    failedThisRun++
                    log("[relay] $name upload failed - will retry later")
                }
                f.delete()
                current = null
                state = State.IDLE
                lastActivityMs = System.currentTimeMillis()
                fetchNext()
            }
        }
    }

    private fun dropCurrent() {
        try { currentWriter?.close() } catch (_: Exception) {}
        currentWriter = null
        currentFile?.delete()
        currentFile = null
        current = null
        if (state == State.RECEIVING || state == State.UPLOADING) state = State.IDLE
    }

    private fun abortToIdle() {
        dropCurrent()
        pending.clear()
        state = State.IDLE
    }

    private fun enabled(): Boolean {
        if (!settings.tripMode) return false
        if (settings.wigleToken.isBlank() && settings.wdgwarsKey.isBlank()) return false
        // Any working internet (cell or WiFi) - the whole point is not needing the rig on WiFi.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** A bare .csv filename with no path parts - the name comes off the wire, so never trust it. */
    private fun safeName(name: String): Boolean =
        name.endsWith(".csv") && !name.contains('/') && !name.contains("..") && name.length in 5..48

        private fun field(line: String, key: String): String? =
        Regex("""\b$key=(\S+)""").find(line)?.groupValues?.get(1)

    private companion object {
        const val MAX_RETRIES = 3
        const val STALL_MS = 20_000L
        const val RELIST_MS = 120_000L   // re-check for new pending files every 2 min
    }
}
