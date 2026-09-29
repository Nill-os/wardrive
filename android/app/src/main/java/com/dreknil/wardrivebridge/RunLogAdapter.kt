package com.dreknil.wardrivebridge

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dreknil.wardrivebridge.databinding.ItemRunLogRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ms since epoch -> "2h14m" / "37m" / "-" - shared by the Analytics run log
 * rows and the Records section's longest-run tile. */
fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "-"
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}

/** Parses a run's first/last observation timestamps (see WardriveDao.runLog())
 * into a real elapsed duration - firstSeenIso is a fixed "yyyy-MM-dd
 * HH:mm:ss" format, so this is exact, not estimated. */
fun RunLogRow.durationMs(isoFormat: SimpleDateFormat): Long {
    val first = firstObsIso ?: return 0L
    val last = lastObsIso ?: return 0L
    return try {
        val start = isoFormat.parse(first)?.time ?: return 0L
        val end = isoFormat.parse(last)?.time ?: return 0L
        end - start
    } catch (e: Exception) {
        0L
    }
}

/** One row per past run, newest first - date/duration/WiFi/BLE counts and
 * the WiFi delta against the row right below it (the previous run
 * chronologically), all real, computed from observations already in the
 * local DB (see WardriveDao's Analytics queries). */
class RunLogAdapter : RecyclerView.Adapter<RunLogAdapter.ViewHolder>() {
    private var rows: List<RunLogRow> = emptyList()
    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun submitList(newRows: List<RunLogRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRunLogRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val prevWifi = if (position + 1 < rows.size) rows[position + 1].wifiCount else null
        holder.bind(row, dateFormat, isoFormat, prevWifi)
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(private val binding: ItemRunLogRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RunLogRow, dateFormat: SimpleDateFormat, isoFormat: SimpleDateFormat, prevWifi: Int?) {
            binding.rowDate.text = dateFormat.format(Date(row.startedAtMs))
            binding.rowDuration.text = formatDurationMs(row.durationMs(isoFormat))
            binding.rowWifi.text = "${row.wifiCount}"
            binding.rowBle.text = "${row.bleCount}"
            if (prevWifi != null) {
                val delta = row.wifiCount - prevWifi
                binding.rowDelta.text = if (delta >= 0) "+$delta" else "$delta"
                binding.rowDelta.setTextColor(Color.parseColor(if (delta >= 0) "#22C55E" else "#FF3333"))
            } else {
                binding.rowDelta.text = "-"
                binding.rowDelta.setTextColor(Color.parseColor("#4A607A"))
            }
        }
    }
}
