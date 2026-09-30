package com.dreknil.wardrivebridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Runtime accent theming. The app's layouts use two accent colours - cyan (primary) and purple
 * (secondary). Rather than re-theme every view by id, this walks the view tree and swaps any view
 * currently drawn in the default accent for the user's chosen one, so the whole UI recolours from
 * two settings. The CYD screen is deliberately left alone (its firmware theme is fixed).
 */
object ThemeManager {
    // The defaults baked into colors.xml - the values we look for when recolouring.
    const val DEFAULT_PRIMARY = 0xFF00F0FF.toInt()   // cyan_500
    const val DEFAULT_SECONDARY = 0xFF9D00FF.toInt() // purple_500

    fun primary(ctx: Context) = AppSettings(ctx).accentPrimary
    fun secondary(ctx: Context) = AppSettings(ctx).accentSecondary

    /** Recolour a screen. Call after the layout is set and in onResume. */
    fun apply(root: View, ctx: Context) {
        val p = primary(ctx)
        val s = secondary(ctx)
        if (p == DEFAULT_PRIMARY && s == DEFAULT_SECONDARY) return // nothing customised
        walk(root, p, s)
    }

    /** Map a default accent to the user's choice, or null if it is neither. */
    private fun remap(color: Int, p: Int, s: Int): Int? = when (color) {
        DEFAULT_PRIMARY -> p
        DEFAULT_SECONDARY -> s
        else -> null
    }

    private fun walk(v: View, p: Int, s: Int) {
        // Solid ColorDrawable background (plain coloured blocks).
        (v.background as? ColorDrawable)?.let {
            remap(it.color, p, s)?.let { c -> v.setBackgroundColor(c) }
        }
        // GradientDrawable / <shape> background - the nav-tab pills and badges
        // (bg_pill_cyan / bg_pill_purple). getColor() is the solid fill (API 24+).
        // Mutate so we recolour this view only, not the shared resource state.
        (v.background as? GradientDrawable)?.let { gd ->
            gd.color?.defaultColor?.let { fill ->
                remap(fill, p, s)?.let { c ->
                    (v.background.mutate() as GradientDrawable).setColor(c)
                }
            }
        }
        // A tinted background (setBackgroundTintList / android:backgroundTint).
        v.backgroundTintList?.defaultColor?.let { tint ->
            remap(tint, p, s)?.let { c -> v.backgroundTintList = ColorStateList.valueOf(c) }
        }
        if (v is TextView) {
            remap(v.currentTextColor, p, s)?.let { c -> v.setTextColor(c) }
        }
        // MaterialButton (the app's outlined "function key" buttons) carries the
        // accent in its stroke and ripple, not its background - recolour those too.
        if (v is MaterialButton) {
            v.strokeColor?.defaultColor?.let { sc ->
                remap(sc, p, s)?.let { c -> v.strokeColor = ColorStateList.valueOf(c) }
            }
            v.rippleColor?.defaultColor?.let { rc ->
                remap(rc, p, s)?.let { c -> v.rippleColor = ColorStateList.valueOf(c) }
            }
        }
        if (v is ImageView) {
            v.imageTintList?.defaultColor?.let { tint ->
                remap(tint, p, s)?.let { c -> v.imageTintList = ColorStateList.valueOf(c) }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), p, s)
    }
}
