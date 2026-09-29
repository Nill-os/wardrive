package com.dreknil.wardrivebridge

/**
 * Best-effort pattern matching on publicly reverse-engineered advertisement
 * formats (not an official API - Apple/Samsung don't publish these). Apple
 * Find My "offline finding" broadcasts (the mode an AirTag uses once
 * separated from its owner) carry Apple's company ID (0x004C) with a 0x12
 * type byte as the first byte of the manufacturer payload - the same
 * signature used by open-source AirTag-detection projects like
 * AirGuard/OpenHaystack. Samsung SmartTag detection is weaker: Samsung's
 * company ID (0x0075) alone is shared by lots of ordinary Samsung
 * accessories, so this only flags it when combined with the short payload
 * length characteristic of a SmartTag beacon - expect more false positives
 * here than for AirTags.
 *
 * Shared by both PhoneBleScanner (reads Android's own parsed
 * manufacturerSpecificData) and RigLinkManager (parses the raw AD structure
 * ble_node relays over wdstream) so a device gets flagged the same way
 * regardless of which radio actually heard it.
 */
object TrackerDetection {
    fun isTracker(companyId: Int, payload: ByteArray): Boolean {
        if (companyId == 0x004C) return payload.isNotEmpty() && payload[0] == 0x12.toByte()
        if (companyId == 0x0075) return payload.size in 2..10
        return false
    }
}
