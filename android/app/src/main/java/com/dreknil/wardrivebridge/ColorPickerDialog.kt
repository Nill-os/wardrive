package com.dreknil.wardrivebridge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** A small RGB colour picker: three sliders and a live swatch. onPicked gets an RGB Int (no alpha). */
object ColorPickerDialog {
    fun show(context: Context, title: String, initialRgb: Int, onPicked: (Int) -> Unit) {
        var r = (initialRgb shr 16) and 0xFF
        var g = (initialRgb shr 8) and 0xFF
        var b = initialRgb and 0xFF

        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pad * 4)
            setBackgroundColor(Color.rgb(r, g, b))
        }
        val hex = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(swatch)
        root.addView(hex)

        fun refresh() {
            swatch.setBackgroundColor(Color.rgb(r, g, b))
            hex.text = String.format("#%02X%02X%02X", r, g, b)
        }
        fun slider(label: String, value: Int, set: (Int) -> Unit) {
            root.addView(TextView(context).apply { text = label })
            root.addView(SeekBar(context).apply {
                max = 255
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { set(p); refresh() }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
        slider("Red", r) { r = it }
        slider("Green", g) { g = it }
        slider("Blue", b) { b = it }
        refresh()

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("OK") { _, _ -> onPicked((r shl 16) or (g shl 8) or b) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
