package com.dreknil.wardrivebridge

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Hands-free run updates while driving: a short spoken summary every few minutes, a warning
 *  when a tracker seems to be following you, and a wrap-up when the run stops. Uses the
 *  navigation audio stream, so it ducks music the way turn-by-turn directions do. */
class SpokenUpdates(
    private val context: Context,
    private val settings: AppSettings,
    private val counts: () -> Pair<Int, Int>,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastTrackerAlertMs = 0L

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
    }

    private fun say(text: String) {
        ensureTts()
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "wardrive-${System.nanoTime()}")
        else handler.postDelayed({ if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "wardrive") }, 1_500)
    }

    private val periodic = object : Runnable {
        override fun run() {
            val minutes = settings.spokenUpdateMinutes
            if (minutes <= 0) return
            val (wifi, ble) = counts()
            say("Wardrive update. $wifi wifi networks, $ble bluetooth devices.")
            handler.postDelayed(this, minutes * 60_000L)
        }
    }

    fun onRunStarted() {
        handler.removeCallbacks(periodic)
        val minutes = settings.spokenUpdateMinutes
        if (minutes > 0) {
            ensureTts()
            handler.postDelayed(periodic, minutes * 60_000L)
        } else if (settings.spokenTrackerAlerts) {
            ensureTts()
        }
    }

    fun onRunStopped(wifi: Int, ble: Int) {
        handler.removeCallbacks(periodic)
        if (settings.spokenUpdateMinutes > 0) say("Run stopped. $wifi wifi networks and $ble bluetooth devices logged.")
    }

    fun trackerAlert() {
        if (!settings.spokenTrackerAlerts) return
        val now = System.currentTimeMillis()
        if (now - lastTrackerAlertMs < TRACKER_ALERT_COOLDOWN_MS) return
        lastTrackerAlertMs = now
        say("Warning. A tracker may be following you.")
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TRACKER_ALERT_COOLDOWN_MS = 5 * 60_000L
    }
}
