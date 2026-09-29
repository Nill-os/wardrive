package com.dreknil.wardrivebridge

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point the car's head unit binds to - this is the only way an app
 * puts UI on the car's own screen, a regular Activity never runs there.
 * Everything the car shows goes through host-rendered templates (see
 * CarDashboardScreen), not a normal layout.
 */
class WardriveCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Allow any host while sideloaded/testing (DHU, then the real car
        // head unit) - HostValidator.ALLOW_ALL_HOSTS_VALIDATOR is what
        // Google's own samples use for apps not yet Play-published, since
        // the production allowlist only matters once distributed there.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = WardriveCarSession()
}
