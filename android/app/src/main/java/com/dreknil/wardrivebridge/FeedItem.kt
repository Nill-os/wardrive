package com.dreknil.wardrivebridge

/** The live feed is a flat list of section headers and rows built fresh
 * from the grouped/deduped state each time something changes - see
 * MainActivity.buildGroupedFeed(). */
sealed class FeedItem {
    data class Header(val source: Source, val title: String, val count: Int, val expanded: Boolean) : FeedItem()
    data class Row(val observation: Observation) : FeedItem()
}
