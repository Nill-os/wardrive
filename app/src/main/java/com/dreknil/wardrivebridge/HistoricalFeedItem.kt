package com.dreknil.wardrivebridge

/** Grouped/collapsible browse view for everything gathered across every
 * past log, mirroring the live feed's file-tree grouping - but by WiFi/BLE/
 * Cell type only, since which radio (rig vs phone) saw a device isn't
 * preserved in the saved CSV format. */
sealed class HistoricalFeedItem {
    data class Header(val groupLabel: String, val count: Int, val expanded: Boolean) : HistoricalFeedItem()
    data class Row(val point: HistoricalPoint) : HistoricalFeedItem()
}
