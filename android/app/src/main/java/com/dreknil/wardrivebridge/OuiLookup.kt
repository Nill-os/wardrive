package com.dreknil.wardrivebridge

/**
 * Offline OUI (first 3 MAC octets) -> vendor lookup, and detection of
 * locally-administered/randomized MAC addresses (which have no real vendor
 * to look up at all - modern OSes rotate these for WiFi probes/BLE
 * advertising specifically to defeat this kind of tracking).
 *
 * This is a curated few hundred entries covering common routers, phones,
 * and IoT brands - not the full IEEE registry (tens of thousands of
 * entries, not worth the APK size for a "nice to know" label). Unknown
 * OUIs just show no vendor rather than guessing.
 */
object OuiLookup {
    // Bit 0x02 of the first octet is the "locally administered" bit - set
    // on any address that was assigned by software rather than burned into
    // hardware at the factory. This is the exact bit Android/iOS flip when
    // generating a randomized MAC for WiFi scanning or BLE advertising, so
    // it's a reliable (not heuristic) signal, unlike the tracker detection
    // in TrackerDetection.kt.
    fun isRandomized(mac: String): Boolean {
        val firstByteHex = mac.substringBefore(':').takeIf { it.length == 2 } ?: return false
        val firstByte = firstByteHex.toIntOrNull(16) ?: return false
        return (firstByte and 0x02) != 0
    }

    fun vendorFor(mac: String): String? {
        if (isRandomized(mac)) return null
        val oui = mac.split(':').take(3).joinToString("") { it.uppercase() }
        if (oui.length != 6) return null
        return table[oui]
    }

    private val table: Map<String, String> = mapOf(
        // Espressif (our own rig's boards)
        "3030F9" to "Espressif", "84F3EB" to "Espressif", "A020A6" to "Espressif",
        "C0491E" to "Espressif", "CC50E3" to "Espressif", "DC4B44" to "Espressif",
        "E064E9" to "Espressif", "EC94CB" to "Espressif", "F4CFA2" to "Espressif",
        "24DCC3" to "Espressif", "34AB95" to "Espressif", "48E729" to "Espressif",

        // Home routers / networking gear
        "001A2B" to "TP-Link", "14CC20" to "TP-Link", "50C7BF" to "TP-Link",
        "60E327" to "TP-Link", "84D826" to "TP-Link", "A42BB0" to "TP-Link",
        "F4F26D" to "TP-Link", "F81A67" to "TP-Link",
        "001B2F" to "Netgear", "204E7F" to "Netgear", "44944F" to "Netgear",
        "84D6D0" to "Netgear", "A040A0" to "Netgear", "C03F0E" to "Netgear",
        "00146C" to "Linksys", "001EE5" to "Linksys", "586D8F" to "Linksys",
        "C4041D" to "Linksys", "EC1A59" to "Linksys",
        "0004ED" to "Asus", "049226" to "Asus", "1C872C" to "Asus",
        "2C56DC" to "Asus", "50465D" to "Asus", "AC220B" to "Asus",
        "245A4C" to "Ubiquiti", "788A20" to "Ubiquiti", "802AA8" to "Ubiquiti",
        "DC9FDB" to "Ubiquiti", "F09FC2" to "Ubiquiti",
        "0018E7" to "Cisco", "0023EA" to "Cisco", "6C2056" to "Cisco",
        "001C10" to "Cisco-Linksys", "000625" to "Cisco",
        "289053" to "Arris", "5C353B" to "Arris", "B0C554" to "Arris",
        "0022B0" to "D-Link", "1C7EE5" to "D-Link", "84C9B2" to "D-Link",
        "C8D3A3" to "D-Link", "F0B4D2" to "D-Link",
        "000C42" to "Mikrotik", "4C5E0C" to "Mikrotik", "6C3B6B" to "Mikrotik",
        "44D9E7" to "Google Nest/OnHub", "F4F5D8" to "Google", "1CF29A" to "Google",
        "3C5AB4" to "Google",

        // Phones / consumer electronics
        "001EC2" to "Apple", "00236C" to "Apple", "0C3021" to "Apple",
        "182032" to "Apple", "28E14C" to "Apple", "3C0754" to "Apple",
        "40331A" to "Apple", "4CB199" to "Apple", "5CF938" to "Apple",
        "68967B" to "Apple", "7073CB" to "Apple", "78CA39" to "Apple",
        "8863DF" to "Apple", "90B21F" to "Apple", "A45E60" to "Apple",
        "B8FF61" to "Apple", "CC088D" to "Apple", "D0817A" to "Apple",
        "DC2B2A" to "Apple", "F0DBF8" to "Apple", "F86FC1" to "Apple",
        "002454" to "Samsung", "0021D1" to "Samsung", "182666" to "Samsung",
        "34145F" to "Samsung", "5C0A5B" to "Samsung", "78259C" to "Samsung",
        "8425DB" to "Samsung", "A48431" to "Samsung", "C4731E" to "Samsung",
        "E8508B" to "Samsung", "F009C7" to "Samsung",
        "3C28A6" to "Google Pixel", "545291" to "Google Pixel", "94EB2C" to "Google Pixel",
        "F41B0B" to "Xiaomi", "50EC50" to "Xiaomi", "64B473" to "Xiaomi",
        "8CBEBE" to "Xiaomi", "F4F5DB" to "Xiaomi",
        "A0999B" to "Huawei", "182715" to "Huawei", "48466A" to "Huawei",
        "F83DFF" to "Huawei",
        "A85B78" to "OnePlus", "94653C" to "OnePlus",

        // Smart home / IoT
        "AC63BE" to "Amazon (Echo/Ringie)", "F0272D" to "Amazon", "44650D" to "Amazon",
        "68372C" to "Amazon", "747548" to "Ring", "A0D795" to "Ring",
        "18B430" to "Nest", "64167E" to "Nest",
        "000E58" to "Sonos", "347E5C" to "Sonos", "5CAAFD" to "Sonos",
        "949F3C" to "Sonos", "B8E937" to "Sonos",
        "ECFABC" to "TP-Link Kasa", "50D4F7" to "TP-Link Kasa",
        "D8B370" to "Wyze", "2CAA8E" to "Wyze",
        "000D93" to "Roku", "B0A737" to "Roku", "CC6D82" to "Roku",
        "001CD8" to "Vizio", "B8F00A" to "Vizio",
        "A81758" to "LG", "10683F" to "LG", "889FFA" to "LG",

        // Computers / misc
        "001A11" to "Google", "000A95" to "Apple", "F4F951" to "Apple",
        "3C15C2" to "Apple", "A4C361" to "Apple",
        "00505A" to "Dell", "18A99B" to "Dell", "B0359F" to "Dell",
        "001C42" to "Parallels (VM)", "080027" to "VirtualBox (VM)",
        "00155D" to "Microsoft (Hyper-V)", "0003FF" to "Microsoft",
        "3417EB" to "Intel", "94654D" to "Intel", "A0A8CD" to "Intel",
        "B8AC6F" to "Dell", "D4BED9" to "Dell",
    )
}
