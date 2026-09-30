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
        ThemeManager.apply(binding.root, this)
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
        binding.alertFlockCheck.isChecked = settings.alertFlock
        binding.alertPoliceCheck.isChecked = settings.alertPolice
        binding.alertSkimmerCheck.isChecked = settings.alertSkimmer
        binding.alertFlipperCheck.isChecked = settings.alertFlipper
        binding.alertDroneCheck.isChecked = settings.alertDrone
        binding.alertMeshCheck.isChecked = settings.alertMesh
        binding.alertGlassesCheck.isChecked = settings.alertGlasses
        binding.alertActionCamCheck.isChecked = settings.alertActionCam
        binding.alertPineappleCheck.isChecked = settings.alertPineapple
        binding.autoUploadCheck.isChecked = settings.autoUploadOnWifi
        binding.tripModeCheck.isChecked = settings.tripMode

        // Keys are masked so they aren't readable over your shoulder; this reveals them briefly.
        binding.showKeysButton.setOnClickListener {
            val masked = binding.wigleTokenInput.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            val type = android.text.InputType.TYPE_CLASS_TEXT or
                if (masked) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.wigleTokenInput.inputType = type
            binding.wdgwarsKeyInput.inputType = type
            binding.showKeysButton.text = if (masked) "> HIDE KEYS" else "> SHOW KEYS"
        }
        // App theme accents
        fun colorBtn(btn: android.widget.Button, name: String, get: () -> Int, set: (Int) -> Unit) {
            fun label() { btn.text = "$name  #%06X".format(get() and 0xFFFFFF) }
            label()
            btn.setOnClickListener {
                ColorPickerDialog.show(this, name, get() and 0xFFFFFF) { rgb -> set(0xFF000000.toInt() or rgb); label() }
            }
        }
        colorBtn(binding.primaryAccentButton, "Primary accent", { settings.accentPrimary }, { settings.accentPrimary = it })
        colorBtn(binding.secondaryAccentButton, "Secondary accent", { settings.accentSecondary }, { settings.accentSecondary = it })
        binding.resetThemeButton.setOnClickListener {
            settings.accentPrimary = ThemeManager.DEFAULT_PRIMARY
            settings.accentSecondary = ThemeManager.DEFAULT_SECONDARY
            Toast.makeText(this, "Theme reset - reopen the app to see it", Toast.LENGTH_SHORT).show()
        }
        // Rig LEDs & screen
        if (settings.rigLedBrightness in 0..100) binding.rigLedBrightnessInput.setText(settings.rigLedBrightness.toString())
        binding.rigScreenBrightnessInput.setText(settings.rigScreenBrightness.toString())
        binding.rigScreenTimeoutInput.setText(settings.rigScreenTimeoutSec.toString())
        binding.rigScreenKeepOnCheck.isChecked = settings.rigScreenKeepOnScanning
        fun rigColorBtn(btn: android.widget.Button, name: String, get: () -> Int, set: (Int) -> Unit) {
            fun label() { btn.text = "$name  #%06X".format(get() and 0xFFFFFF) }
            label()
            btn.setOnClickListener { ColorPickerDialog.show(this, name, get() and 0xFFFFFF) { rgb -> set(rgb and 0xFFFFFF); label() } }
        }
        rigColorBtn(binding.rigLedApButton, "AP seen", { settings.rigLedAp }, { settings.rigLedAp = it })
        rigColorBtn(binding.rigLedBleButton, "BLE seen", { settings.rigLedBle }, { settings.rigLedBle = it })
        rigColorBtn(binding.rigLedOkButton, "OK / start / stop", { settings.rigLedOk }, { settings.rigLedOk = it })
        rigColorBtn(binding.rigLedFailButton, "Error / fail", { settings.rigLedFail }, { settings.rigLedFail = it })

        showPairedRig()
        binding.forgetRigButton.setOnClickListener {
            // rigLink is lateinit - only touch it on a service that got far enough to set it.
            val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
            if (link != null) link.forgetRig() else settings.pairedRigAddress = ""
            showPairedRig()
            Toast.makeText(this, "Rig forgotten - pair again from the rig's CFG tab", Toast.LENGTH_LONG).show()
        }
        binding.useCurrentLocationButton.setOnClickListener { fillCurrentLocation() }
        binding.serviceModeButton.setOnClickListener {
            val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
            if (link == null || ScanService.instance?.rigConnected != true) {
                Toast.makeText(this, "Connect to the rig first", Toast.LENGTH_SHORT).show()
            } else {
                link.sendRaw("rig service on")
                Toast.makeText(this, "Rig joining WiFi… the URL will pop up on the dashboard", Toast.LENGTH_LONG).show()
                finish() // back to the dashboard, where the URL dialog appears
            }
        }
        binding.saveSettingsButton.setOnClickListener { save() }
    }

    /** Push the LED/screen settings to the rig over Bluetooth (it applies live and saves them). */
    private fun pushRigVisualSettings() {
        val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
        if (link == null || ScanService.instance?.rigConnected != true) {
            Toast.makeText(this, "Rig LED/screen settings saved - they'll apply when the rig connects", Toast.LENGTH_SHORT).show()
            return
        }
        link.sendRaw("cfg led_brightness ${settings.rigLedBrightness}")
        link.sendRaw("cfg screen_brightness ${settings.rigScreenBrightness}")
        link.sendRaw("cfg screen_timeout_sec ${settings.rigScreenTimeoutSec}")
        link.sendRaw("cfg screen_keep_on_scanning ${if (settings.rigScreenKeepOnScanning) 1 else 0}")
        link.sendRaw("cfg led_color_ap %06X".format(settings.rigLedAp and 0xFFFFFF))
        link.sendRaw("cfg led_color_ble %06X".format(settings.rigLedBle and 0xFFFFFF))
        link.sendRaw("cfg led_color_ok %06X".format(settings.rigLedOk and 0xFFFFFF))
        link.sendRaw("cfg led_color_fail %06X".format(settings.rigLedFail and 0xFFFFFF))
        Toast.makeText(this, "Sent LED/screen settings to the rig", Toast.LENGTH_SHORT).show()
    }

    /** Push changed rig network settings (WiFi credentials + the service-mode
     *  password) to the rig over the bonded Bluetooth link. Only non-empty
     *  fields are sent (blank = keep what the rig already has), and the secret
     *  fields are cleared from the screen afterwards so passwords don't linger.
     *  These are never stored in the phone's own settings. */
    private fun pushRigNetworkSettings() {
        val fields = listOf(
            "wifi_ssid" to binding.rigWifiSsidInput.text.toString(),
            "wifi_pass" to binding.rigWifiPassInput.text.toString(),
            "backup_wifi_ssid" to binding.rigBackupSsidInput.text.toString(),
            "backup_wifi_pass" to binding.rigBackupPassInput.text.toString(),
            "service_password" to binding.rigServicePassInput.text.toString(),
        ).filter { it.second.isNotEmpty() }
        if (fields.isEmpty()) return
        val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
        if (link == null || ScanService.instance?.rigConnected != true) {
            Toast.makeText(this, "Connect to the rig first to change its network settings", Toast.LENGTH_LONG).show()
            return
        }
        for ((k, v) in fields) link.sendRaw("cfg $k $v")
        // Don't leave secrets on screen after sending them.
        binding.rigWifiPassInput.setText("")
        binding.rigBackupPassInput.setText("")
        binding.rigServicePassInput.setText("")
        Toast.makeText(this, "Sent ${fields.size} network setting(s) to the rig", Toast.LENGTH_SHORT).show()
    }

    /** Push the detection-alert toggles to the rig so its own on-screen/LED
     *  alerts match the phone's. Silently no-ops if the rig isn't connected -
     *  they're saved on the phone and (re)pushed on the next connected save. */
    private fun pushRigAlertSettings() {
        val link = try { ScanService.instance?.rigLink } catch (_: UninitializedPropertyAccessException) { null }
        if (link == null || ScanService.instance?.rigConnected != true) return
        fun b(v: Boolean) = if (v) "1" else "0"
        link.sendRaw("cfg alert_flock ${b(settings.alertFlock)}")
        link.sendRaw("cfg alert_police ${b(settings.alertPolice)}")
        link.sendRaw("cfg alert_skimmer ${b(settings.alertSkimmer)}")
        link.sendRaw("cfg alert_flipper ${b(settings.alertFlipper)}")
        link.sendRaw("cfg alert_glasses ${b(settings.alertGlasses)}")
        link.sendRaw("cfg alert_actioncam ${b(settings.alertActionCam)}")
        link.sendRaw("cfg alert_pineapple ${b(settings.alertPineapple)}")
        // drone + mesh are phone-only (need BLE service UUIDs the rig doesn't relay).
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
        settings.alertFlock = binding.alertFlockCheck.isChecked
        settings.alertPolice = binding.alertPoliceCheck.isChecked
        settings.alertSkimmer = binding.alertSkimmerCheck.isChecked
        settings.alertFlipper = binding.alertFlipperCheck.isChecked
        settings.alertDrone = binding.alertDroneCheck.isChecked
        settings.alertMesh = binding.alertMeshCheck.isChecked
        settings.alertGlasses = binding.alertGlassesCheck.isChecked
        settings.alertActionCam = binding.alertActionCamCheck.isChecked
        settings.alertPineapple = binding.alertPineappleCheck.isChecked
        pushRigAlertSettings()
        settings.autoUploadOnWifi = binding.autoUploadCheck.isChecked
        settings.rigLedBrightness = binding.rigLedBrightnessInput.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: settings.rigLedBrightness
        settings.rigScreenBrightness = binding.rigScreenBrightnessInput.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: settings.rigScreenBrightness
        settings.rigScreenTimeoutSec = binding.rigScreenTimeoutInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: settings.rigScreenTimeoutSec
        settings.rigScreenKeepOnScanning = binding.rigScreenKeepOnCheck.isChecked
        pushRigVisualSettings()
        pushRigNetworkSettings()
        settings.tripMode = binding.tripModeCheck.isChecked
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
