package com.dreknil.wardrivebridge

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.dreknil.wardrivebridge.databinding.DialogHuntBinding

/**
 * Fox-hunting proximity mode for a single already-detected device: shows
 * the strongest current reading across whichever radios (rig WiFi/BLE,
 * phone WiFi/BLE) currently hear it, as a simple hot/cold label plus
 * vibration and an audible tone (pitch rises with strength) - walk/drive
 * toward "hot", away from "cold".
 */
class HuntDialog(
    context: Context,
    private val targetMac: String,
    private val targetLabel: String,
) : Dialog(context) {
    private val binding = DialogHuntBinding.inflate(layoutInflater)
    private val handler = Handler(Looper.getMainLooper())

    private val perSource = mutableMapOf<Source, Pair<Int, Long>>() // source -> (rssi, lastSeenMs)
    private var ticksSinceVibrate = 0

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // Classic fox-hunting-receiver feel: an audible beep alongside the
    // vibration, pitch rising as signal strengthens - a second sensory
    // channel that works even with the phone in a pocket or mount where
    // vibration alone might not be felt. ToneGenerator only offers preset
    // DTMF tone pairs (no arbitrary frequency control), so "pitch" here
    // means picking a higher-frequency digit as strength increases, not a
    // continuously swept tone.
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (e: RuntimeException) {
        null // no audio output available - vibration-only fallback, not fatal
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        setContentView(binding.root)
        binding.huntTitle.text = targetLabel.ifBlank { targetMac }
        binding.huntMac.text = targetMac
        binding.huntCloseButton.setOnClickListener { dismiss() }
        binding.huntRssi.text = "--"
        binding.huntTrend.text = "Waiting for a signal…"
        binding.huntSources.text = ""
        handler.post(tick)
    }

    // Cleanup lives here, not in setOnDismissListener, because the caller sets
    // its own OnDismissListener (to null out its reference) and a Dialog only
    // keeps ONE - so a dismiss listener here would be silently overwritten,
    // leaving the tick loop (and its beep/vibrate) running after the dialog
    // closed. dismiss() is called for the STOP button, back press and outside
    // tap alike (cancel() routes through it), so this covers every close path.
    override fun dismiss() {
        handler.removeCallbacks(tick)
        try { toneGenerator?.stopTone() } catch (_: Exception) {}
        try { toneGenerator?.release() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        super.dismiss()
    }

    fun matches(mac: String): Boolean = mac.equals(targetMac, ignoreCase = true)

    fun onSample(obs: Observation) {
        perSource[obs.source] = obs.rssi to System.currentTimeMillis()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val stale = perSource.filter { now - it.value.second > LOSS_THRESHOLD_MS }.keys
        stale.forEach { perSource.remove(it) }

        if (perSource.isEmpty()) {
            binding.huntRssi.text = "--"
            binding.huntTrend.text = "No signal yet"
            binding.huntTrend.setTextColor(Color.parseColor("#7A7A92"))
            binding.huntSources.text = ""
            return
        }

        binding.huntSources.text = perSource.entries
            .sortedByDescending { it.value.first }
            .joinToString("  ·  ") { "${it.key.label} ${it.value.first}dBm" }

        val best = perSource.values.maxOf { it.first }
        binding.huntRssi.text = "$best dBm"

        val clamped = best.coerceIn(-100, -30)
        val strength = (clamped + 100) / 70.0 // 0 = cold/far, 1 = hot/close
        val (label, color) = hotColdLabel(strength)
        binding.huntTrend.text = label
        binding.huntTrend.setTextColor(color)
        binding.huntRssi.setTextColor(color)

        maybeVibrate(strength)
    }

    private fun hotColdLabel(strength: Double): Pair<String, Int> = when {
        strength >= 0.85 -> "HOT" to Color.parseColor("#EF4444")
        strength >= 0.6 -> "Warm" to Color.parseColor("#F59E0B")
        strength >= 0.35 -> "Cool" to Color.parseColor("#00F0FF")
        else -> "Cold" to Color.parseColor("#3B82F6")
    }

    // Pulses on a fixed cadence rather than one-per-sample, so it reads as
    // smooth proximity feedback instead of stuttering with scan cadence.
    private fun maybeVibrate(strength: Double) {
        ticksSinceVibrate++
        if (ticksSinceVibrate < VIBRATE_EVERY_N_TICKS) return
        ticksSinceVibrate = 0

        if (strength < 0.05) return // too weak to bother buzzing/beeping

        val v = vibrator
        if (v != null && v.hasVibrator()) {
            val amplitude = (60 + strength * 195).toInt().coerceIn(1, 255)
            val durationMs = (40 + (1 - strength) * 60).toLong()
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        }

        val toneType = when {
            strength >= 0.85 -> ToneGenerator.TONE_DTMF_9 // highest-pitched pair - HOT
            strength >= 0.6 -> ToneGenerator.TONE_DTMF_7
            strength >= 0.35 -> ToneGenerator.TONE_DTMF_5
            else -> ToneGenerator.TONE_DTMF_2 // lowest-pitched pair - Cold
        }
        toneGenerator?.startTone(toneType, 80)
    }

    companion object {
        private const val TICK_MS = 500L
        private const val VIBRATE_EVERY_N_TICKS = 2 // ~1s between pulses
        // Generous on purpose: the phone's own WiFi scan only refreshes
        // every 15s (and the rig's channel-hopping capture is on a similar
        // cadence), so a real device easily goes 20-30s between sightings
        // without actually leaving range. 45s comfortably covers that.
        private const val LOSS_THRESHOLD_MS = 45_000L
    }
}
