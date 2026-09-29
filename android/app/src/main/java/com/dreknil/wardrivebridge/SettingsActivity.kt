package com.dreknil.wardrivebridge

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dreknil.wardrivebridge.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.wigleTokenInput.setText(settings.wigleToken)
        binding.wdgwarsKeyInput.setText(settings.wdgwarsKey)
        if (settings.homeLat != 0.0 || settings.homeLon != 0.0) {
            binding.homeLatInput.setText(settings.homeLat.toString())
            binding.homeLonInput.setText(settings.homeLon.toString())
        }
        if (settings.homeRadiusM != 0.0) binding.homeRadiusInput.setText(settings.homeRadiusM.toString())
        binding.macBlacklistInput.setText(settings.macBlacklistRaw)
        binding.ssidBlacklistInput.setText(settings.ssidBlacklistRaw)
        binding.retentionDaysInput.setText(settings.retentionDays.toString())
        if (settings.maxHdop != 0.0) binding.maxHdopInput.setText(settings.maxHdop.toString())
        if (settings.spokenUpdateMinutes > 0) binding.spokenMinutesInput.setText(settings.spokenUpdateMinutes.toString())
        binding.spokenTrackerCheck.isChecked = settings.spokenTrackerAlerts
        binding.autoUploadCheck.isChecked = settings.autoUploadOnWifi
        binding.uploadRigViaPhoneCheck.isChecked = settings.uploadRigViaPhone

        // Keys are masked so they aren't readable over your shoulder; this reveals them briefly.
        binding.showKeysButton.setOnClickListener {
            val masked = binding.wigleTokenInput.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            val type = android.text.InputType.TYPE_CLASS_TEXT or
                if (masked) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.wigleTokenInput.inputType = type
            binding.wdgwarsKeyInput.inputType = type
            binding.showKeysButton.text = if (masked) "> HIDE KEYS" else "> SHOW KEYS"
        }
        showPairedRig()
        binding.forgetRigButton.setOnClickListener {
            // rigLink is lateinit - only touch it on a service that got far enough to set it.
            val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
            if (link != null) link.forgetRig() else settings.pairedRigAddress = ""
            showPairedRig()
            Toast.makeText(this, "Rig forgotten - pair again from the rig's CFG tab", Toast.LENGTH_LONG).show()
        }
        binding.useCurrentLocationButton.setOnClickListener { fillCurrentLocation() }
        binding.saveSettingsButton.setOnClickListener { save() }
    }

    private fun showPairedRig() {
        val addr = settings.pairedRigAddress
        binding.pairedRigText.text = if (addr.isEmpty()) {
            "No rig paired. On the rig, open the CFG tab and tap PAIR PHONE, then start a run here - " +
                "Android will ask for the 6-digit code shown on the rig's screen."
        } else {
            "Paired with rig $addr. This phone only connects to that rig."
        }
        binding.forgetRigButton.visibility = if (addr.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun fillCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
            }
            .maxByOrNull { it.time }
        if (loc == null) {
            Toast.makeText(this, "No recent fix available - start a run first, then come back", Toast.LENGTH_LONG).show()
            return
        }
        binding.homeLatInput.setText(loc.latitude.toString())
        binding.homeLonInput.setText(loc.longitude.toString())
    }

    private fun save() {
        settings.wigleToken = binding.wigleTokenInput.text.toString().trim()
        settings.wdgwarsKey = binding.wdgwarsKeyInput.text.toString().trim()
        settings.homeLat = binding.homeLatInput.text.toString().toDoubleOrNull() ?: 0.0
        settings.homeLon = binding.homeLonInput.text.toString().toDoubleOrNull() ?: 0.0
        settings.homeRadiusM = binding.homeRadiusInput.text.toString().toDoubleOrNull() ?: 0.0
        settings.macBlacklistRaw = binding.macBlacklistInput.text.toString()
        settings.ssidBlacklistRaw = binding.ssidBlacklistInput.text.toString()
        settings.retentionDays = binding.retentionDaysInput.text.toString().toIntOrNull() ?: 0
        settings.maxHdop = binding.maxHdopInput.text.toString().toDoubleOrNull() ?: 0.0
        settings.spokenUpdateMinutes = binding.spokenMinutesInput.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 0
        settings.spokenTrackerAlerts = binding.spokenTrackerCheck.isChecked
        settings.autoUploadOnWifi = binding.autoUploadCheck.isChecked
        settings.uploadRigViaPhone = binding.uploadRigViaPhoneCheck.isChecked
        val wantsSpeech = settings.spokenUpdateMinutes > 0 || settings.spokenTrackerAlerts
        val hasTtsEngine = packageManager.queryIntentServices(
            android.content.Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
        ).isNotEmpty()
        if (wantsSpeech && !hasTtsEngine) {
            Toast.makeText(this, "Saved - but this phone has no text-to-speech engine, so nothing will be spoken. Install one (e.g. Google Speech Services or RHVoice).", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
