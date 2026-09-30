package com.dreknil.wardrivebridge

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.dreknil.wardrivebridge.databinding.DialogAntennaBinding

/**
 * Antenna checker for a single selected device: shows the live relative signal
 * (RSSI) from a chosen target, with a peak hold and a rolling average, so you
 * can swap antennas and see which one pulls the target in strongest. Capture A
 * with one antenna, swap, capture B with the next, and it reports which is
 * stronger and by how many dB.
 *
 * RSSI is only relative - it depends on distance, orientation and interference -
 * so keep the phone/rig and the target still while comparing, and change only
 * the antenna between captures.
 */
class AntennaCheckDialog(
    context: Context,
    private val targetMac: String,
    private val targetLabel: String,
) : Dialog(context) {
    private val binding = DialogAntennaBinding.inflate(layoutInflater)
    private val handler = Handler(Looper.getMainLooper())

    private val perSource = mutableMapOf<Source, Pair<Int, Long>>() // source -> (rssi, lastSeenMs)

    // Stats since the last RESET (or since open).
    private var peak = Int.MIN_VALUE
    private var sum = 0L
    private var count = 0L

    private var captureA: Snapshot? = null
    private var captureB: Snapshot? = null

    private data class Snapshot(val peak: Int, val avg: Int, val samples: Long)

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        setContentView(binding.root)
        binding.antTitle.text = targetLabel.ifBlank { targetMac }
        binding.antMac.text = targetMac
        binding.antCloseButton.setOnClickListener { dismiss() }
        binding.antResetButton.setOnClickListener { resetStats() }
        binding.antCaptureAButton.setOnClickListener { captureA = snapshot(); updateCompare() }
        binding.antCaptureBButton.setOnClickListener { captureB = snapshot(); updateCompare() }
        setOnDismissListener { handler.removeCallbacks(tick) }
        handler.post(tick)
    }

    fun matches(mac: String): Boolean = mac.equals(targetMac, ignoreCase = true)

    fun onSample(obs: Observation) {
        perSource[obs.source] = obs.rssi to System.currentTimeMillis()
        // Accumulate on the strongest current reading across whatever radios hear it.
        val best = perSource.values.maxOf { it.first }
        if (best > peak) peak = best
        sum += best
        count++
    }

    private fun snapshot(): Snapshot? {
        if (count == 0L) return null
        return Snapshot(peak, (sum / count).toInt(), count)
    }

    private fun resetStats() {
        peak = Int.MIN_VALUE; sum = 0; count = 0
        binding.antCompare.text = ""
        binding.antStats.text = "Peak --   ·   Avg --"
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        perSource.filter { now - it.value.second > LOSS_THRESHOLD_MS }.keys.forEach { perSource.remove(it) }

        if (perSource.isEmpty()) {
            binding.antRssi.text = "--"
            binding.antRssi.setTextColor(Color.parseColor("#7A7A92"))
            binding.antBar.progress = 0
            binding.antSources.text = "No signal (target out of range?)"
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

        binding.antSources.text = perSource.entries
            .sortedByDescending { it.value.first }
            .joinToString("  ·  ") { "${it.key.label} ${it.value.first}dBm" }
    }

    private fun updateCompare() {
        val a = captureA; val b = captureB
        if (a == null && b == null) { binding.antCompare.text = ""; return }
        val parts = mutableListOf<String>()
        if (a != null) parts.add("A: peak ${a.peak} / avg ${a.avg} dBm")
        if (b != null) parts.add("B: peak ${b.peak} / avg ${b.avg} dBm")
        if (a != null && b != null) {
            // Higher (less negative) dBm = stronger. Compare on peak.
            val diff = b.peak - a.peak
            val verdict = when {
                diff > 0 -> "B stronger by ${diff} dB"
                diff < 0 -> "A stronger by ${-diff} dB"
                else -> "A and B tied"
            }
            parts.add("→ $verdict")
        }
        binding.antCompare.text = parts.joinToString("\n")
    }

    private fun strengthColor(strength: Double): Int = when {
        strength >= 0.85 -> Color.parseColor("#EF4444")
        strength >= 0.6 -> Color.parseColor("#F59E0B")
        strength >= 0.35 -> Color.parseColor("#00F0FF")
        else -> Color.parseColor("#3B82F6")
    }

    companion object {
        private const val TICK_MS = 400L
        // Same generous window as the fox hunt: phone WiFi scans refresh ~every
        // 15s, so a real target can go 20-30s between sightings without leaving.
        private const val LOSS_THRESHOLD_MS = 45_000L
    }
}
