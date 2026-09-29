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

        binding.useCurrentLocationButton.setOnClickListener { fillCurrentLocation() }
        binding.saveSettingsButton.setOnClickListener { save() }
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
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
