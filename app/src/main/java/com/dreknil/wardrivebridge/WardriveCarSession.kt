package com.dreknil.wardrivebridge

import androidx.car.app.Screen
import androidx.car.app.Session

class WardriveCarSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen = CarDashboardScreen(carContext)
}
