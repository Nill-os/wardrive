package com.dreknil.wardrivebridge

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FloorPlanMarker(val xFrac: Float, val yFrac: Float, val label: String, val timestampIso: String)

/** Floor-plan images live in app-external-files/floorplans/, each with a
 * `<name>.json` sidecar holding its tap-placed markers - same sidecar-file
 * convention LogNotes/the firmware's .uploaded marker already use elsewhere
 * in this project. Marker positions are stored as fractions (0..1) of the
 * image's own width/height, not pixels, so they stay correct regardless of
 * what size the view ends up drawing the image at. There's no GPS involved
 * at all here - this is a manual, indoor-only positioning aid for the cases
 * (parking garages, offices, malls) where GPS doesn't work in the first
 * place. */
object FloorPlanStore {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dir(context: Context): File {
        val d = File(context.getExternalFilesDir(null), "floorplans")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun listFloorPlans(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && !f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun importImage(context: Context, uri: Uri): File? {
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = if (mime.contains("/")) mime.substringAfterLast('/') else "img"
        val dest = File(dir(context), "plan_${System.currentTimeMillis()}.$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.exists() && dest.length() > 0) dest else null
        } catch (e: Exception) {
            null
        }
    }

    private fun markersFile(imageFile: File): File = File(imageFile.parentFile, "${imageFile.name}.json")

    fun loadMarkers(imageFile: File): MutableList<FloorPlanMarker> {
        val file = markersFile(imageFile)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<FloorPlanMarker>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(FloorPlanMarker(
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.optString("label", ""), o.optString("ts", ""),
                ))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveMarkers(imageFile: File, markers: List<FloorPlanMarker>) {
        val arr = JSONArray()
        for (m in markers) {
            arr.put(JSONObject().apply {
                put("x", m.xFrac)
                put("y", m.yFrac)
                put("label", m.label)
                put("ts", m.timestampIso)
            })
        }
        markersFile(imageFile).writeText(arr.toString())
    }

    fun nowIso(): String = isoFormat.format(Date())

    fun delete(imageFile: File) {
        imageFile.delete()
        markersFile(imageFile).delete()
    }
}
