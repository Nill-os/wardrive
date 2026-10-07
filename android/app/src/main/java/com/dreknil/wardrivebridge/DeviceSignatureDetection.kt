package com.dreknil.wardrivebridge

/**
 * Best-effort pattern matching on publicly documented BLE signatures for
 * three device categories worth knowing about nearby, same spirit as
 * TrackerDetection's AirTag/SmartTag heuristic - not an official API from
 * any of these manufacturers, just reverse-engineered patterns the security
 * research community has published and open-source detector tools already
 * rely on. Expect some false positives/negatives, same caveat as the
 * tracker heuristic.
 */
object DeviceSignatureDetection {
    // Flipper Zero: its registered IEEE OUI is 0C:FA:22; 80:E1:26/80:E1:27
    // were used before that assignment and are still seen on older units.
    // Devices also default to advertising a local name starting with
    // "Flipper" unless the owner renamed it. MAC-based detection is
    // trivially spoofable (well documented by the same tools that use it,
    // e.g. "Wall of Flippers") - this is a "worth a look" signal, not proof.
    private val FLIPPER_MAC_PREFIXES = listOf("0C:FA:22", "80:E1:26", "80:E1:27")

    fun isFlipperZero(mac: String, name: String): Boolean {
        val macUpper = mac.uppercase()
        if (FLIPPER_MAC_PREFIXES.any { macUpper.startsWith(it) }) return true
        return name.startsWith("Flipper", ignoreCase = true)
    }

    // Flock Safety ALPR cameras: B4:1E:52 is Flock's own registered OUI;
    // 00:03:7F is Qualcomm Atheros (the QCA9377 radio their hardware uses,
    // seen on units still carrying the chip vendor's default MAC). Their
    // battery-pack accessories advertise recognizable names ("Penguin-"
    // followed by a 10-digit serial, "FS Battery", or "DfuTarg" during a
    // firmware update), and manufacturer ID 0x09C8 (XUNTONG) shows up in
    // their BLE advertisements even from units that don't broadcast a name -
    // the strongest single signal of the group, per public field research.
    private val FLOCK_MAC_PREFIXES = listOf("B4:1E:52", "00:03:7F")
    private const val FLOCK_COMPANY_ID = 0x09C8

    fun isFlockSafety(mac: String, name: String, companyId: Int?): Boolean {
        val macUpper = mac.uppercase()
        if (FLOCK_MAC_PREFIXES.any { macUpper.startsWith(it) }) return true
        if (companyId == FLOCK_COMPANY_ID) return true
        if (name.startsWith("Penguin-", ignoreCase = true)) return true
        if (name.equals("FS Battery", ignoreCase = true)) return true
        if (name.equals("DfuTarg", ignoreCase = true)) return true
        return false
    }

    // BLE credit-card skimmers are 99% of the time built around cheap,
    // off-the-shelf serial-BLE breakout modules left at their factory
    // default name - HC-05/06/08/03 and FREE2MOVE are the names named
    // specifically in skimmer teardown writeups and commercial skimmer-
    // detector tools (e.g. BlueSleuth). A device broadcasting one of these
    // exact names isn't proof of a skimmer (plenty of legitimate hobbyist
    // BLE projects use the same modules) - it just means "worth a closer
    // look," same as every other heuristic here.
    private val SKIMMER_NAMES = listOf("HC-05", "HC-06", "HC-08", "HC-03", "FREE2MOVE")

    fun isSkimmer(name: String): Boolean {
        if (name.isBlank()) return false
        return SKIMMER_NAMES.any { name.equals(it, ignoreCase = true) }
    }

    // ASTM F3411 / OpenDroneID Remote ID - service UUID 0xFFFA is the
    // standardized broadcast every compliant drone Remote ID module uses
    // (FAA-mandated in the US since 2023). Real, standardized signature,
    // not a heuristic guess - unlike everything else in this file, this one
    // is an actual spec, not reverse-engineering. Phone-only: the rig's BLE
    // relay doesn't carry service UUIDs (see Observation.serviceUuids'
    // own comment), so this can only ever be evaluated from PhoneBleScanner.
    private const val DRONE_REMOTE_ID_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"

    fun isDrone(serviceUuids: List<String>): Boolean =
        serviceUuids.any { it.equals(DRONE_REMOTE_ID_UUID, ignoreCase = true) }

    // Meshtastic's own published BLE service UUID (from their firmware's
    // BluetoothCommon code) - real, not reverse-engineered. Same phone-only
    // caveat as isDrone() above.
    private const val MESHTASTIC_SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd" // meshtastic/firmware BluetoothCommon.h (was mistyped ...eaf4, which never matched)

    fun isMeshRadio(serviceUuids: List<String>): Boolean =
        serviceUuids.any { it.equals(MESHTASTIC_SERVICE_UUID, ignoreCase = true) }

    // Weak, low-confidence name-based heuristic (unlike the UUID/company-ID
    // signals above) - Ray-Ban Meta smart glasses at their default
    // advertised name. Expect false negatives if the wearer renamed the
    // device or a future model uses a different naming scheme.
    fun isGlasses(name: String): Boolean =
        name.startsWith("Ray-Ban", ignoreCase = true) || name.startsWith("Meta", ignoreCase = true)

    // Same weak-heuristic caveat as isGlasses() - default advertised names
    // for GoPro/Insta360 action cameras, easily changed by the owner.
    fun isActionCam(name: String): Boolean =
        name.startsWith("GoPro", ignoreCase = true) || name.startsWith("Insta360", ignoreCase = true)

    // Same weak-heuristic caveat as isGlasses() - Axon body/fleet camera
    // default advertised name.
    fun isPoliceCam(name: String): Boolean = name.startsWith("AXON", ignoreCase = true)

    // The WEAKEST heuristic here - WiFi Pineapple's default AP naming
    // convention only. Only catches units left at default/stock naming; a
    // renamed or "stealth mode" Pineapple won't be caught by this at all.
    // WiFi-only (SSID, not a BLE name) - see PhoneWifiScanner/RigLinkManager
    // for where this is called.
    fun isPineapple(ssid: String): Boolean = ssid.contains("pineapple", ignoreCase = true)
}
