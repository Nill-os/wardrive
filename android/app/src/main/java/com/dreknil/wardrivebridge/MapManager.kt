package com.dreknil.wardrivebridge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Owns the osmdroid MapView shown in the Map tab. Live markers track the
 * same per-run dedup key as the feed (source+mac), so a repeat sighting
 * moves its existing marker instead of stacking a new one. A separate
 * historical overlay is used when viewing past log file(s) from the Logs
 * tab, independent of whatever the live run is currently showing. Tapping
 * any dot - live or historical - reports back through onMarkerInfo with a
 * title and full details string for the caller to display.
 */
class MapManager(
    context: Context,
    private val mapView: MapView,
    private val onMarkerInfo: (title: String, body: String, mac: String) -> Unit,
) {
    private val liveMarkers = mutableMapOf<String, Marker>()
    private val liveData = mutableMapOf<String, Observation>()
    private val historicalMarkers = mutableListOf<Marker>()
    private var hasCentered = false
    private var heatmapEnabled = false

    // The actual driven route, not just detected-device dots - added once
    // here (before any markers exist) so it always stays under them in the
    // overlay draw order.
    private val trail = Polyline().apply {
        outlinePaint.color = Color.parseColor("#9900F0FF")
        outlinePaint.strokeWidth = 9f
    }

    // Also added once up front (right after the trail, before any dot
    // markers) so it stays under every marker in draw order - it's a
    // density backdrop, not something that should occlude the dots.
    private val heatmapOverlay = HeatmapOverlay()

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidTileCache = File(context.getExternalFilesDir(null), "osmdroid/tiles")
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)
        mapView.overlays.add(trail)
        mapView.overlays.add(heatmapOverlay)
    }

    fun setHeatmapEnabled(enabled: Boolean) {
        heatmapEnabled = enabled
        refreshHeatmap()
    }

    private fun refreshHeatmap() {
        if (!heatmapEnabled) {
            heatmapOverlay.setPoints(emptyList())
        } else {
            val points = mutableListOf<GeoPoint>()
            liveData.values.filterNot { it.lat == 0.0 && it.lon == 0.0 }
                .forEach { points.add(GeoPoint(it.lat, it.lon)) }
            historicalMarkers.forEach { points.add(it.position) }
            heatmapOverlay.setPoints(points)
        }
        mapView.invalidate()
    }

    fun addTrailPoint(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        trail.addPoint(point)
        if (!hasCentered) {
            mapView.controller.setCenter(point)
            hasCentered = true
        }
        mapView.invalidate()
    }

    fun upsertLive(obs: Observation) {
        if (obs.lat == 0.0 && obs.lon == 0.0) return
        val key = "${obs.source}|${obs.mac}"
        val point = GeoPoint(obs.lat, obs.lon)
        val marker = liveMarkers.getOrPut(key) {
            val m = Marker(mapView)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = coloredDot(obs.source.color)
            m.relatedObject = key
            m.setOnMarkerClickListener { clicked, _ ->
                val k = clicked.relatedObject as? String
                val current = k?.let { liveData[it] }
                if (current != null) onMarkerInfo(current.label.ifBlank { current.mac }, formatLive(current), current.mac)
                true
            }
            mapView.overlays.add(m)
            m
        }
        marker.position = point
        liveData[key] = obs

        if (!hasCentered) {
            mapView.controller.setCenter(point)
            hasCentered = true
        }
        refreshHeatmap()
    }

    fun clearLive() {
        liveMarkers.values.forEach { mapView.overlays.remove(it) }
        liveMarkers.clear()
        liveData.clear()
        trail.setPoints(emptyList())
        hasCentered = false
        refreshHeatmap()
    }

    fun recenterOnLive() {
        val point = liveMarkers.values.lastOrNull()?.position ?: return
        mapView.controller.setCenter(point)
    }

    fun recenterOn(lat: Double, lon: Double) {
        mapView.controller.setCenter(GeoPoint(lat, lon))
    }

    /** True once the map has been centered on real data (a marker, a trail
     *  point, or an explicit recenter) - lets the caller supply a fallback
     *  center only when there's nothing else to show. */
    fun isCentered(): Boolean = hasCentered

    /** Center on this point only if nothing has centered the map yet, so the
     *  live map opens on the user's location instead of Null Island (0,0)
     *  when there's no active run or GPS fix to center on. */
    fun ensureCentered(lat: Double, lon: Double) {
        if (hasCentered) return
        mapView.controller.setCenter(GeoPoint(lat, lon))
        hasCentered = true
    }

    fun showHistorical(points: List<HistoricalPoint>) {
        clearHistorical()
        for (p in points) {
            val marker = Marker(mapView)
            marker.position = GeoPoint(p.lat, p.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = coloredDot(historicalTypeColor(p.type))
            marker.relatedObject = p
            marker.setOnMarkerClickListener { clicked, _ ->
                val point = clicked.relatedObject as? HistoricalPoint
                if (point != null) onMarkerInfo(point.label.ifBlank { point.mac }, point.formatDetails(), point.mac)
                true
            }
            historicalMarkers.add(marker)
            mapView.overlays.add(marker)
        }
        points.firstOrNull()?.let { mapView.controller.setCenter(GeoPoint(it.lat, it.lon)) }
        refreshHeatmap()
    }

    fun clearHistorical() {
        historicalMarkers.forEach { mapView.overlays.remove(it) }
        historicalMarkers.clear()
        refreshHeatmap()
    }

    private fun formatLive(obs: Observation): String = buildString {
        appendLine("Source: ${obs.source.label}")
        appendLine("MAC: ${obs.mac}")
        appendLine("Label: ${obs.label.ifBlank { "(hidden)" }}")
        appendLine("Type/Auth: ${obs.authOrType}")
        if (obs.channel > 0) appendLine("Channel: ${obs.channel}")
        if (obs.channelWidthMHz > 0) appendLine("Channel width: ${obs.channelWidthMHz} MHz")
        if (obs.frequencyMHz > 0) appendLine("Frequency: ${obs.frequencyMHz} MHz")
        appendLine("RSSI: ${obs.rssi} dBm")
        if (obs.rsrp != 0) appendLine("RSRP: ${obs.rsrp}")
        if (obs.rsrq != 0) appendLine("RSRQ: ${obs.rsrq}")
        if (obs.vendor != null) appendLine("Vendor: ${obs.vendor}")
        else if (obs.macRandomized) appendLine("Vendor: unknown (randomized MAC)")
        appendLine("Location: %.6f, %.6f".format(obs.lat, obs.lon))
        if (obs.accuracyM > 0) appendLine("GPS accuracy: ±${obs.accuracyM.toInt()} m")
        appendLine("First seen: ${obs.firstSeenIso}")
        if (obs.isReturning) appendLine("Seen in a previous run too")
        if (obs.isTracker) appendLine("⚠ Possible AirTag/SmartTag")
    }.trim()

    // Only a handful of distinct colors exist (one per Source, plus a few
    // historical types), but showHistorical() can create one marker per
    // point in a whole log file (thousands, via "view all logs on map") -
    // caching by color avoids allocating a fresh, functionally-identical
    // GradientDrawable per marker. Safe to share one instance across
    // multiple Markers: osmdroid only ever reads/draws the icon, never
    // mutates it per-marker.
    private val dotCache = mutableMapOf<Int, GradientDrawable>()

    // GradientDrawable has no intrinsic size by default (getIntrinsicWidth/
    // Height() return -1) - osmdroid's Marker sizes and positions its icon
    // from those intrinsic dimensions, not from bounds set here, so without
    // setSize() every marker was drawing at zero size (invisible) even
    // though it was actually being added to the map correctly.
    private fun coloredDot(color: Int): GradientDrawable = dotCache.getOrPut(color) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke(3, Color.WHITE)
        d.setSize(36, 36)
        d.setBounds(0, 0, 36, 36)
        d
    }
}
