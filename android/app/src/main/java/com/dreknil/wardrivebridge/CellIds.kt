package com.dreknil.wardrivebridge

/**
 * One canonical spelling for a cell tower's identity "TYPE-MCC-MNC-AREA-CID". The MNC is written
 * with its real digit count - 3 digits in the countries that use 3-digit network codes (US "310-004",
 * Mexico "334-020"), at least 2 elsewhere (Germany "262-01") - which is how Android reports it
 * (mncString) and what WiGLE's key needs (MCCMNC_AREA_CID). Older app versions stored the MNC as a
 * plain number ("310-4"), so the same tower ended up under two identities; AppDatabase's 5->6
 * migration rewrites those rows with this, and CsvExporter keys every export with it.
 */
object CellIds {
    // MCCs whose networks use 3-digit MNCs (ITU-T E.212 allocations: North America and the
    // Caribbean, and the Latin American countries that adopted 3-digit codes).
    private val THREE_DIGIT_MNC_MCCS = setOf(
        302, 310, 311, 312, 313, 314, 315, 316, 334, 338, 342, 344, 346, 348, 352, 354, 356, 358, 360,
        365, 366, 376, 708, 722, 732, 750,
    )

    /** The MNC padded to its country's length; unknown or non-numeric values are returned as-is. */
    fun canonicalMnc(mcc: String, mnc: String): String {
        val mccN = mcc.toIntOrNull() ?: return mnc
        if (mnc.isEmpty() || !mnc.all { it.isDigit() }) return mnc
        return mnc.padStart(if (mccN in THREE_DIGIT_MNC_MCCS) 3 else 2, '0')
    }

    /** The identity with its MNC canonicalised; CDMA ("CDMA-SID-NID-BID") and anything else
     *  unexpected is returned unchanged. */
    fun canonical(identity: String): String {
        val p = identity.split('-')
        if (p.size != 5 || p[0] == "CDMA") return identity
        val mnc = canonicalMnc(p[1], p[2])
        return if (mnc == p[2]) identity else "${p[0]}-${p[1]}-$mnc-${p[3]}-${p[4]}"
    }
}
