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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    private val perSource = mutableMapOf<Source, Pair<Int, Long>>()
    private var peak = Int.MIN_VALUE
    private var sum = 0L
    private var count = 0L
    private var captureA: Snapshot? = null
    private var captureB: Snapshot? = null
    private data class Snapshot(val peak: Int, val avg: Int)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as ScanService.LocalBinder).service
            scanService = bound
            bound.listener = this@AntennaActivity
            updateScanButton(bound.running)
            refreshTargets()
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
            if (s.running) s.stopRun(notifyRig = true, reason = "antenna check")
            else s.startRun(notifyRig = true, reason = "antenna check")
            updateScanButton(s.running)
        }
        binding.antRefreshButton.setOnClickListener { refreshTargets() }
        binding.antResetButton.setOnClickListener { resetStats() }
        binding.antCaptureAButton.setOnClickListener { captureA = snapshot(); updateCompare() }
        binding.antCaptureBButton.setOnClickListener { captureB = snapshot(); updateCompare() }

        binding.antTargetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mac = macOptions.getOrNull(position)
                if (mac != null && mac != targetMac) { targetMac = mac; resetStats() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ScanService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        scanService?.listener = null
        try { unbindService(connection) } catch (_: Exception) {}
        scanService = null
    }

    // ---- target list ----
    private fun refreshTargets() {
        val s = scanService ?: return
        // Every device currently heard, strongest first, deduped across sources.
        val bestByMac = LinkedHashMap<String, Observation>()
        for ((_, map) in s.groups) for ((mac, obs) in map) {
            val cur = bestByMac[mac]
            if (cur == null || obs.rssi > cur.rssi) bestByMac[mac] = obs
        }
        val sorted = bestByMac.values.sortedByDescending { it.rssi }
        macOptions = sorted.map { it.mac }
        val labels = sorted.map {
            val name = it.label.ifBlank { it.mac }
            "$name  ·  ${it.source.label}  ·  ${it.rssi}dBm"
        }
        val display = if (labels.isEmpty()) listOf("No devices yet - start scanning") else labels
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, display)
        binding.antTargetSpinner.adapter = adapter
        // keep the current target selected if it's still present
        val idx = macOptions.indexOf(targetMac)
        if (idx >= 0) binding.antTargetSpinner.setSelection(idx)
        else if (macOptions.isNotEmpty()) { targetMac = macOptions[0]; resetStats() }
    }

    private fun updateScanButton(running: Boolean) {
        binding.antScanButton.text = if (running) "> STOP SCANNING" else "> START SCANNING"
    }

    // ---- meter ----
    private fun snapshot(): Snapshot? =
        if (count == 0L) null else Snapshot(peak, (sum / count).toInt())

    private fun resetStats() {
        peak = Int.MIN_VALUE; sum = 0; count = 0; perSource.clear()
        captureA = null; captureB = null
        binding.antCompare.text = ""
        binding.antStats.text = "Peak --   ·   Avg --"
    }

    private fun refreshMeter() {
        val now = System.currentTimeMillis()
        perSource.filter { now - it.value.second > 45_000L }.keys.forEach { perSource.remove(it) }
        if (targetMac == null || perSource.isEmpty()) {
            binding.antRssi.text = "--"
            binding.antRssi.setTextColor(Color.parseColor("#7A7A92"))
            binding.antBar.progress = 0
            binding.antSources.text = if (targetMac == null) "" else "No signal (target out of range?)"
            return
        }
        val best = perSource.values.maxOf { it.first }
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
        if (!tagged.mac.equals(targetMac, ignoreCase = true)) return
        perSource[tagged.source] = tagged.rssi to System.currentTimeMillis()
        val best = perSource.values.maxOf { it.first }
        if (best > peak) peak = best
        sum += best; count++
    }
    override fun onRunStateChanged(running: Boolean) { updateScanButton(running); if (running) refreshTargets() }
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
