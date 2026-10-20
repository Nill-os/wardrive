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

    // Flock Safety ALPR cameras: B4:1E:52 is Flock's own registered OUI. Their
    // accessories advertise recognizable names - "Penguin-" + serial,
    // "Pigvision", "FS Battery"/"FS Ext Battery", and "DfuTarg" during a
    // firmware update - all confirmed as literal strings in the decompiled
    // Biscuit Manager app (com.example.biscuitmanager, v1.0.21.1), whose own
    // Flock classifier is a user-configurable OUI matcher (FLOCK/META/AXON/
    // IGNORE categories). Manufacturer ID 0x09C8 also shows up in their BLE
    // advertisements even from units that don't broadcast a name.
    //
    // 00:03:7F (Qualcomm Atheros) was removed 2026-10-19: it was never verified
    // against any source and is an extremely common WiFi-chip OUI, so matching
    // it as a prefix mislabeled large numbers of ordinary devices as Flock.
    private val FLOCK_MAC_PREFIXES = listOf("B4:1E:52")
    private const val FLOCK_COMPANY_ID = 0x09C8

    fun isFlockSafety(mac: String, name: String, companyId: Int?): Boolean {
        val macUpper = mac.uppercase()
        if (FLOCK_MAC_PREFIXES.any { macUpper.startsWith(it) }) return true
        if (companyId == FLOCK_COMPANY_ID) return true
        if (name.startsWith("Penguin", ignoreCase = true)) return true   // "Penguin-<serial>" camera, or bare "Penguin"
        if (name.startsWith("Pigvision", ignoreCase = true)) return true  // Flock's Pigvision camera line (seen in Biscuit Manager)
        if (name.equals("FS Battery", ignoreCase = true)) return true
        if (name.equals("FS Ext Battery", ignoreCase = true)) return true // Flock external battery pack
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

    // Nearby WiFi/BLE attack tools and DIY wardrive gear worth knowing about -
    // the same "counter-surveillance" category the Biscuit Manager app detects.
    // Two signals, both low-confidence (easily renamed/spoofed, same caveat as
    // every heuristic here):
    //  - Default advertised names of common ESP32/RPi tools.
    //  - BLE service UUIDs that these boards ship with: the Nordic UART Service
    //    (used by Pwnagotchi, Flipper, most ESP32/nRF serial tools), the two
    //    Arduino ESP32 BLE example UUIDs (Marauder/GhostESP/DIY flock+wardrive
    //    firmware), and the Nordic DFU firmware-update service.
    private val HACK_TOOL_NAMES = listOf(
        "pwnagotchi", "marauder", "ghostesp", "ghost esp", "bruce", "minino", "deauther", "esp32marauder",
    )
    private val HACK_TOOL_UUIDS = listOf(
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e", // Nordic UART Service (NUS)
        "4fafc201-1fb5-459e-8fcc-c5c9c331914b", // ESP32 Arduino BLE example service
        "beb5483e-36e1-4688-b7f5-ea07361b26a8", // ESP32 Arduino BLE example characteristic/service
        "00001530-1212-efde-1523-785feabcd123", // Nordic DFU (firmware update mode)
    )

    fun isHackTool(name: String, serviceUuids: List<String>): Boolean {
        val lower = name.lowercase()
        if (HACK_TOOL_NAMES.any { lower.contains(it) }) return true
        return serviceUuids.any { u -> HACK_TOOL_UUIDS.any { it.equals(u, ignoreCase = true) } }
    }

    // Weak, low-confidence name-based heuristic (unlike the UUID/company-ID
    // signals above) - Ray-Ban Meta smart glasses at their default
    // advertised name. Expect false negatives if the wearer renamed the
    // device or a future model uses a different naming scheme.
    // Ray-Ban Meta smart glasses, plus Meta Quest / Oculus VR headsets (same
    // "wearable camera/mic worth knowing is nearby" category; name patterns
    // from Biscuit Manager's MetaDevice classifier). "rayban" with no hyphen
    // is a real alternate advertised form.
    fun isGlasses(name: String): Boolean {
        val n = name.trim()
        if (n.startsWith("Ray-Ban", ignoreCase = true) || n.startsWith("rayban", ignoreCase = true)) return true
        if (n.startsWith("Meta", ignoreCase = true)) return true
        val lower = n.lowercase()
        return lower.startsWith("quest") || lower.startsWith("oculus quest") || lower.startsWith("oculus")
    }

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
