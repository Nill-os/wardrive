package com.dreknil.wardrivebridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Renders a floor-plan bitmap fit-centered in the view, plus tap-placed
 * markers on top. Deliberately no pan/zoom - floor plans are viewed at
 * whatever fit-to-screen size they land at, matching the "simple manual
 * grid" scope this feature was asked for, not a full mapping tool. */
class FloorPlanView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var markers: List<FloorPlanMarker> = emptyList()
    private val imageMatrix = Matrix()
    private var imageRect = RectF()

    // xFrac/yFrac of the tapped point within the image; hitIndex is the
    // marker index the tap landed on, if any (for editing/removing instead
    // of dropping a new one on top of it).
    var onImageTap: ((xFrac: Float, yFrac: Float, hitIndex: Int?) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444"); style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A7A92"); textSize = 36f; textAlign = Paint.Align.CENTER
    }

    private val markerRadiusPx = 22f

    fun setImage(bmp: Bitmap?) {
        bitmap = bmp
        recomputeMatrix()
        invalidate()
    }

    fun setMarkers(list: List<FloorPlanMarker>) {
        markers = list
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (width - drawW) / 2f
        val top = (height - drawH) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(left, top)
        imageRect = RectF(left, top, left + drawW, top + drawH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("Import a floor plan to get started", width / 2f, height / 2f, emptyPaint)
            return
        }
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        for (m in markers) {
            val px = imageRect.left + m.xFrac * imageRect.width()
            val py = imageRect.top + m.yFrac * imageRect.height()
            canvas.drawCircle(px, py, markerRadiusPx, markerFillPaint)
            canvas.drawCircle(px, py, markerRadiusPx, markerStrokePaint)
            if (m.label.isNotBlank()) canvas.drawText(m.label, px + markerRadiusPx + 8f, py + 12f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        if (bitmap == null || imageRect.width() <= 0f || !imageRect.contains(event.x, event.y)) return true

        val xFrac = (event.x - imageRect.left) / imageRect.width()
        val yFrac = (event.y - imageRect.top) / imageRect.height()

        var hitIndex: Int? = null
        val hitRadiusPx = markerRadiusPx * 1.5f
        for ((i, m) in markers.withIndex()) {
            val px = imageRect.left + m.xFrac * imageRect.width()
            val py = imageRect.top + m.yFrac * imageRect.height()
            val dx = px - event.x
            val dy = py - event.y
            if (dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx) {
                hitIndex = i
                break
            }
        }

        onImageTap?.invoke(xFrac, yFrac, hitIndex)
        return true
    }
}
