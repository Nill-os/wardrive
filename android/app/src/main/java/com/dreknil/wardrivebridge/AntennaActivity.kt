package com.dreknil.wardrivebridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.dreknil.wardrivebridge.databinding.ActivityAntennaBinding

/**
 * Antenna checker as its own screen (reached from Settings). Pick a target
 * device and read its live relative signal, with peak hold and A/B capture so
 * you can swap antennas and see which one pulls the target in strongest. RSSI
 * only flows while scanning, so this screen can start/stop scanning itself.
 *
 * It becomes the ScanService listener while visible (MainActivity releases its
 * listener when it's not in the foreground), and hands it back on the way out.
 */
class AntennaActivity : AppCompatActivity(), ScanService.SessionListener {
    private lateinit var binding: ActivityAntennaBinding
    private val handler = Handler(Looper.getMainLooper())

    private var scanService: ScanService? = null
    private var targetMac: String? = null
    private var macOptions: List<String> = emptyList()
    // Which radio to read the target's signal from. null = strongest across all
    // that hear it. Picking Source.RIG_WIFI/RIG_BLE isolates the rig's antenna
    // (that's the whole point of testing an antenna on the rig), and RIG_BLE /
    // PHONE_BLE let you antenna-check a Bluetooth target, not just WiFi.
    private var selectedSource: Source? = null

    private val perSource = mutableMapOf<Source, Pair<Int, Long>>()
    private var peak = Int.MIN_VALUE
    private var sum = 0L
    private var count = 0L
    private var captureA: Snapshot? = null
    private var captureB: Snapshot? = null
    private data class Snapshot(val peak: Int, val avg: Int)
    // After capturing A the meter pauses so you can physically swap antennas
    // without the swap's junk readings polluting B's window; RESUME un-pauses.
    private var swapPaused = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as ScanService.LocalBinder).service
            scanService = bound
            bound.listener = this@AntennaActivity
            updateScanButton()
            updateTargetLabel()
            updateSourceLabel()
        }
        override fun onServiceDisconnected(name: ComponentName?) { scanService = null }
    }

    private val tick = object : Runnable {
        override fun run() { refreshMeter(); handler.postDelayed(this, 400L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAntennaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "Antenna check"
        // Optional pre-selected target (e.g. launched from a device long-press).
        targetMac = intent.getStringExtra(EXTRA_TARGET_MAC)

        binding.antScanButton.setOnClickListener {
            val s = scanService ?: return@setOnClickListener
            // Live signal without logging a drive: preview scanning turns on the
            // phone radios AND asks the rig to scan (so RIG WiFi/BLE readings are
            // available to test the rig's antenna), with no run/GPS/DB writes. If
            // a real run happens to be active, leave it be - it already feeds us.
            if (s.running) s.stopRun(notifyRig = true, reason = "antenna check")
            else if (s.previewing) s.stopPreview()
            else s.startPreview(wifi = true, ble = true, cell = false)
            updateScanButton()
        }
        binding.antTargetButton.setOnClickListener { showTargetPicker() }
        binding.antSourceButton.setOnClickListener { showSourcePicker() }
        binding.antResetButton.setOnClickListener { resetStats() }
        // Each capture snapshots the current window, then clears the live
        // peak/avg so the next antenna is measured from scratch - otherwise B's
        // average carries over all of A's samples. Flow: aim antenna 1, Capture
        // A; it pauses so you can swap antennas, tap RESUME; Capture B; read it.
        binding.antCaptureAButton.setOnClickListener {
            captureA = snapshot(); resetLive(); swapPaused = true; updateCompare(); updateResumeButton()
        }
        binding.antCaptureBButton.setOnClickListener { captureB = snapshot(); resetLive(); updateCompare() }
        binding.antResumeButton.setOnClickListener {
            swapPaused = false; resetLive(); updateResumeButton()
        }
        updateTargetLabel()
        updateSourceLabel()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ScanService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        // Don't leave the radios (and the rig) scanning once we're off-screen;
        // a real run, if any, keeps going (stopPreview no-ops while running).
        scanService?.stopPreview()
        scanService?.listener = null
        try { unbindService(connection) } catch (_: Exception) {}
        scanService = null
    }

    // ---- target selection ----
    // Snapshot of every device currently heard, strongest first, deduped across
    // sources - the choices shown when you tap SELECT TARGET.
    private fun currentDevices(): List<Observation> {
        val s = scanService ?: return emptyList()
        val bestByMac = LinkedHashMap<String, Observation>()
        for ((_, map) in s.groups) for ((mac, obs) in map) {
            val cur = bestByMac[mac]
            if (cur == null || obs.rssi > cur.rssi) bestByMac[mac] = obs
        }
        return bestByMac.values.sortedByDescending { it.rssi }
    }

    private fun showTargetPicker() {
        val devices = currentDevices()
        if (devices.isEmpty()) {
            val scanning = scanService?.let { it.running || it.previewing } == true
            android.widget.Toast.makeText(
                this,
                if (scanning) "No devices heard yet - give it a few seconds"
                else "Tap START SCANNING first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        macOptions = devices.map { it.mac }
        val labels = devices.map {
            val name = it.label.ifBlank { it.mac }
            "$name\n${it.source.label} · ${it.rssi} dBm · ${it.mac}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select target")
            .setItems(labels) { _, which ->
                targetMac = macOptions.getOrNull(which)
                resetStats()
                updateTargetLabel(devices.getOrNull(which))
            }
            .show()
    }

    private fun updateTargetLabel(obs: Observation? = null) {
        binding.antTargetLabel.text = when {
            targetMac == null -> "No target selected"
            obs != null -> "Target: ${obs.label.ifBlank { obs.mac }}  (${obs.mac})"
            else -> "Target: $targetMac"
        }
    }

    private fun updateScanButton() {
        val scanning = scanService?.let { it.running || it.previewing } == true
        binding.antScanButton.text = if (scanning) "> STOP SCANNING" else "> START SCANNING"
    }

    // ---- radio/source selection ----
    // The rig's two radios (RIG WiFi = wifi_node, RIG BLE = ble_node) are the
    // rig's "nodes" for this single-rig setup. Per-sniffer-node selection with
    // several ESP-NOW nodes needs the rig to tag each sighting with its node
    // index, which its WD:AP/WD:BLE stream doesn't carry yet.
    private val sourceChoices: List<Pair<String, Source?>> = listOf(
        "Strongest (any radio)" to null,
        "Rig WiFi (wifi_node antenna)" to Source.RIG_WIFI,
        "Rig BLE (ble_node antenna)" to Source.RIG_BLE,
        "Phone WiFi" to Source.PHONE_WIFI,
        "Phone Bluetooth" to Source.PHONE_BLE,
    )

    private fun showSourcePicker() {
        val labels = sourceChoices.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Measure from which radio?")
            .setItems(labels) { _, which ->
                selectedSource = sourceChoices[which].second
                resetStats()
                updateSourceLabel()
            }
            .show()
    }

    private fun updateSourceLabel() {
        val name = sourceChoices.firstOrNull { it.second == selectedSource }?.first ?: "Strongest (any radio)"
        binding.antSourceButton.text = "> RADIO: ${name.substringBefore(" (").uppercase()}"
        binding.antSourceLabel.text = when (selectedSource) {
            null -> "Measuring the strongest radio that hears the target. Pick the rig's radio to test the rig's antenna."
            Source.RIG_WIFI -> "Measuring the rig's wifi_node only - swap its antenna and compare."
            Source.RIG_BLE -> "Measuring the rig's ble_node only."
            Source.PHONE_WIFI -> "Measuring the phone's WiFi radio only."
            Source.PHONE_BLE -> "Measuring the phone's Bluetooth radio only."
            else -> ""
        }
    }

    // The reading to display/track, honoring the chosen radio: a specific source
    // when one is picked (null if it isn't currently heard on that radio), or the
    // strongest across every radio that hears the target when set to "any".
    private fun selectedBest(): Int? {
        val entries = if (selectedSource == null) perSource.values
        else perSource.filterKeys { it == selectedSource }.values
        return if (entries.isEmpty()) null else entries.maxOf { it.first }
    }

    // ---- meter ----
    private fun snapshot(): Snapshot? =
        if (count == 0L) null else Snapshot(peak, (sum / count).toInt())

    // Clear only the live meter accumulators (peak/avg window), keeping any A/B
    // captures - used after a capture so the next antenna starts fresh.
    private fun resetLive() {
        peak = Int.MIN_VALUE; sum = 0; count = 0; perSource.clear()
        binding.antStats.text = "Peak --   ·   Avg --"
    }

    private fun resetStats() {
        resetLive()
        captureA = null; captureB = null
        swapPaused = false
        binding.antCompare.text = ""
        updateResumeButton()
    }

    private fun updateResumeButton() {
        binding.antResumeButton.visibility = if (swapPaused) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refreshMeter() {
        if (swapPaused) {
            binding.antRssi.text = "⏸"
            binding.antRssi.setTextColor(Color.parseColor("#F59E0B"))
            binding.antBar.progress = 0
            binding.antStats.text = "Paused - swap the antenna, then tap RESUME"
            binding.antSources.text = ""
            return
        }
        val now = System.currentTimeMillis()
        perSource.filter { now - it.value.second > 45_000L }.keys.forEach { perSource.remove(it) }
        val best = selectedBest()
        if (targetMac == null || best == null) {
            binding.antRssi.text = "--"
            binding.antRssi.setTextColor(Color.parseColor("#7A7A92"))
            binding.antBar.progress = 0
            binding.antSources.text = when {
                targetMac == null -> ""
                selectedSource != null -> "No signal on ${selectedSource!!.label} (target not heard on that radio?)"
                else -> "No signal (target out of range?)"
            }
            return
        }
        binding.antRssi.text = "$best dBm"
        val strength = ((best.coerceIn(-100, -30) + 100) / 70.0)
        binding.antRssi.setTextColor(strengthColor(strength))
        binding.antBar.progress = (strength * 100).toInt()
        val avg = if (count > 0) (sum / count).toInt() else best
        val peakText = if (peak == Int.MIN_VALUE) "--" else "$peak"
        binding.antStats.text = "Peak $peakText dBm   ·   Avg $avg dBm"
        binding.antSources.text = perSource.entries.sortedByDescending { it.value.first }
            .joinToString("  ·  ") { "${it.key.label} ${it.value.first}dBm" }
    }

    private fun updateCompare() {
        val a = captureA; val b = captureB
        val parts = mutableListOf<String>()
        if (a != null) parts.add("A: peak ${a.peak} / avg ${a.avg} dBm")
        if (b != null) parts.add("B: peak ${b.peak} / avg ${b.avg} dBm")
        if (a != null && b != null) {
            val diff = b.peak - a.peak
            parts.add("→ " + when {
                diff > 0 -> "B stronger by $diff dB"
                diff < 0 -> "A stronger by ${-diff} dB"
                else -> "A and B tied"
            })
        }
        binding.antCompare.text = parts.joinToString("\n")
    }

    private fun strengthColor(strength: Double): Int = when {
        strength >= 0.85 -> Color.parseColor("#EF4444")
        strength >= 0.6 -> Color.parseColor("#F59E0B")
        strength >= 0.35 -> Color.parseColor("#00F0FF")
        else -> Color.parseColor("#3B82F6")
    }

    // ---- ScanService.SessionListener (only the samples + run state matter here) ----
    override fun onObservation(tagged: Observation) {
        if (swapPaused) return // ignore readings while you're swapping antennas
        if (!tagged.mac.equals(targetMac, ignoreCase = true)) return
        perSource[tagged.source] = tagged.rssi to System.currentTimeMillis()
        // Peak/avg track the radio being measured, so a strong reading from a
        // radio you're NOT testing can't skew the antenna comparison.
        val best = selectedBest() ?: return
        if (best > peak) peak = best
        sum += best; count++
    }
    override fun onRunStateChanged(running: Boolean) { updateScanButton() }
    override fun onRigConnected() {}
    override fun onRigDisconnected() {}
    override fun onMeshLinkStateChanged(state: RigLinkManager.MeshLinkState) {}
    override fun onPauseStateChanged(paused: Boolean) {}
    override fun onLocationChanged(location: android.location.Location) {}
    override fun onPersistentTrackerAlert(tagged: Observation, previousSighting: HistoricalPoint) {}
    override fun onRigLogLine(line: String) {}

    companion object {
        const val EXTRA_TARGET_MAC = "target_mac"
        fun intent(context: Context, targetMac: String? = null): Intent =
            Intent(context, AntennaActivity::class.java).apply {
                if (targetMac != null) putExtra(EXTRA_TARGET_MAC, targetMac)
            }
    }
}
