package com.dreknil.wardrivebridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * A real heatmap, drawn each frame from the current point set - not the
 * "GPU-accelerated" density matrix the original wishlist described (that
 * doesn't correspond to anything osmdroid exposes); this is a plain
 * Canvas overlay with a soft radial gradient per point, which visually
 * reads the same way: overlapping points naturally compound into hotter
 * (more opaque) spots through ordinary alpha blending, no special blend
 * mode needed.
 */
class HeatmapOverlay : Overlay() {
    private var points: List<GeoPoint> = emptyList()

    fun setPoints(newPoints: List<GeoPoint>) {
        points = newPoints
    }

    // draw() runs on every map redraw (pan/zoom/invalidate), and `points`
    // can be thousands long ("view all logs" heatmap) - a fresh Paint +
    // RadialGradient per point per frame was a real per-frame allocation
    // storm. The gradient's shape never changes (same radius/colors), only
    // its screen position does - build it once centered at the origin and
    // translate the canvas per point instead of rebuilding the shader.
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, RADIUS_PX,
            intArrayOf(Color.argb(70, 239, 68, 68), Color.argb(0, 239, 68, 68)),
            null, Shader.TileMode.CLAMP,
        )
    }
    private val reusablePoint = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val projection = mapView.projection
        for (p in points) {
            projection.toPixels(p, reusablePoint)
            canvas.save()
            canvas.translate(reusablePoint.x.toFloat(), reusablePoint.y.toFloat())
            canvas.drawCircle(0f, 0f, RADIUS_PX, dotPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val RADIUS_PX = 45f
    }
}
