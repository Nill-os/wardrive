package com.dreknil.wardrivebridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.floor

/**
 * Draws a 0.5° × 0.5° coverage grid over the map.  Each cell that
 * contains at least one observation gets a translucent fill whose
 * opacity scales with observation count, up to a cap - giving a
 * quick visual read of "where have I wardrived" distinct from the
 * per-device signal heatmap.  Based on Wardrive Go's dense-cell
 * coverage layer (§4 of the REA upgrade plan).
 */
class CoverageGridOverlay : Overlay() {
    // Map from grid-cell key (latFloor * 1e6 + lonFloor) to observation count.
    private var cells: Map<Long, Int> = emptyMap()

    fun setPoints(points: List<GeoPoint>) {
        val counts = mutableMapOf<Long, Int>()
        for (p in points) {
            val key = cellKey(p.latitude, p.longitude)
            counts[key] = (counts[key] ?: 0) + 1
        }
        cells = counts
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val reusableRect = RectF()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val proj = mapView.projection
        val bounds: BoundingBox = mapView.boundingBox ?: return
        // Expand slightly so edge cells aren't clipped.
        val minLat = floor(bounds.latSouth / CELL_DEG) * CELL_DEG - CELL_DEG
        val maxLat = floor(bounds.latNorth / CELL_DEG) * CELL_DEG + CELL_DEG
        val minLon = floor(bounds.lonWest / CELL_DEG) * CELL_DEG - CELL_DEG
        val maxLon = floor(bounds.lonEast / CELL_DEG) * CELL_DEG + CELL_DEG

        var lat = minLat
        while (lat <= maxLat) {
            var lon = minLon
            while (lon <= maxLon) {
                val count = cells[cellKey(lat, lon)]
                if (count != null) {
                    val alpha = (40 + (count.coerceAtMost(DENSE_CAP) * 90 / DENSE_CAP)).coerceIn(40, 130)
                    fillPaint.color = Color.argb(alpha, 0, 240, 255) // cyan tint
                    val tl = proj.toPixels(GeoPoint(lat + CELL_DEG, lon), null)
                    val br = proj.toPixels(GeoPoint(lat, lon + CELL_DEG), null)
                    reusableRect.set(tl.x.toFloat(), tl.y.toFloat(), br.x.toFloat(), br.y.toFloat())
                    if (!reusableRect.isEmpty) canvas.drawRect(reusableRect, fillPaint)
                }
                lon += CELL_DEG
            }
            lat += CELL_DEG
        }
    }

    companion object {
        const val CELL_DEG = 0.5
        private const val DENSE_CAP = 50
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        val latFloor = (floor(lat / CELL_DEG) * 1000).toLong()
        val lonFloor = (floor(lon / CELL_DEG) * 1000).toLong()
        return latFloor * 1_000_000L + lonFloor
    }
}
