package com.dreknil.wardrivebridge

/** Offline MCC/MNC -> carrier name lookup for the cell-tower carrier
 * breakdown stat. Covers the major US carriers plus their common MVNO/
 * roaming MNCs; unrecognized combinations just show the raw MCC-MNC
 * instead of guessing. Cell identity strings look like
 * "LTE-<mcc>-<mnc>-<tac>-<ci>" (see PhoneCellScanner), so the MCC/MNC are
 * pulled from the second and third hyphen-separated fields. */
object CarrierLookup {
    private val table: Map<String, String> = mapOf(
        "310-410" to "AT&T", "310-560" to "AT&T", "310-680" to "AT&T", "310-070" to "AT&T",
        "310-150" to "AT&T", "310-380" to "AT&T",
        "310-260" to "T-Mobile", "310-160" to "T-Mobile", "310-200" to "T-Mobile",
        "310-210" to "T-Mobile", "310-220" to "T-Mobile", "310-230" to "T-Mobile",
        "310-240" to "T-Mobile", "310-250" to "T-Mobile", "310-270" to "T-Mobile",
        "310-280" to "T-Mobile", "310-300" to "T-Mobile", "310-310" to "T-Mobile",
        "310-490" to "Sprint (T-Mobile)", "311-490" to "Sprint (T-Mobile)",
        "310-004" to "Verizon", "310-010" to "Verizon", "310-012" to "Verizon",
        "310-013" to "Verizon", "311-480" to "Verizon", "310-590" to "Verizon",
        "310-890" to "Verizon", "310-910" to "Verizon",
        "312-530" to "US Cellular", "311-580" to "US Cellular",
        "310-120" to "Sprint (T-Mobile)",
    )

    fun carrierFor(cellIdentity: String): String? {
        val parts = cellIdentity.split('-')
        if (parts.size < 3) return null
        val mcc = parts[1]
        val mnc = parts[2]
        if (mcc == "?" || mnc == "?") return null
        val key = "$mcc-${mnc.padStart(3, '0')}"
        return table[key] ?: table["$mcc-$mnc"]
    }
}
