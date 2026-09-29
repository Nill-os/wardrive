package com.dreknil.wardrivebridge

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dreknil.wardrivebridge.databinding.ItemObservationBinding

/**
 * Grouped feed: section headers (one per non-empty source, e.g. "RIG WIFI
 * (12)") followed by that source's deduped rows - see
 * MainActivity.buildGroupedFeed() for how the list is built.
 *
 * submitList() replaces the whole backing list and calls
 * notifyDataSetChanged() rather than trying to compute minimal diffs by
 * hand - the previous incremental add()/notifyItemInserted() version had a
 * real bug where a trim-then-notify mismatch silently desynced
 * RecyclerView's internal position tracking and crashed after running for a
 * while. A full-list rebuild on every change is simpler and can't drift out
 * of sync the same way; given a deduped feed tops out at realistic
 * unique-device counts (not thousands), the extra redraw cost is
 * negligible.
 */
class ObservationAdapter(
    private val onHeaderClick: (Source) -> Unit,
    private val onRowLongClick: (Observation) -> Unit,
    private val onRowClick: (Observation) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<FeedItem> = emptyList()

    fun submitList(newItems: List<FeedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun clear() {
        items = emptyList()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is FeedItem.Header -> VIEW_TYPE_HEADER
        is FeedItem.Row -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false) as TextView, onHeaderClick)
        } else {
            RowViewHolder(ItemObservationBinding.inflate(inflater, parent, false), onRowLongClick, onRowClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FeedItem.Header -> (holder as HeaderViewHolder).bind(item)
            is FeedItem.Row -> (holder as RowViewHolder).bind(item.observation)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(
        private val view: TextView,
        onHeaderClick: (Source) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private var boundSource: Source? = null

        init {
            view.setOnClickListener { boundSource?.let(onHeaderClick) }
        }

        fun bind(header: FeedItem.Header) {
            boundSource = header.source
            val arrow = if (header.expanded) "▼" else "▶"
            view.text = "$arrow  ${header.title.uppercase()}  ·  ${header.count}"
        }
    }

    class RowViewHolder(
        private val binding: ItemObservationBinding,
        private val onRowLongClick: (Observation) -> Unit,
        private val onRowClick: (Observation) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bound: Observation? = null

        init {
            binding.root.setOnLongClickListener { bound?.let(onRowLongClick); true }
            binding.root.setOnClickListener { bound?.let(onRowClick) }
        }

        fun bind(obs: Observation) {
            bound = obs
            val name = obs.label.ifBlank { "(hidden)" }
            binding.title.text = "$name  ·  ${obs.mac}"
            binding.title.setTextColor(if (obs.isTracker) Color.parseColor("#EF4444") else Color.parseColor("#E8E8F0"))
            binding.subtitle.text = buildString {
                if (obs.isTracker) append("TRACKER (possible AirTag/SmartTag)  ·  ")
                append(obs.authOrType)
                if (obs.channel > 0) append("  ch${obs.channel}")
                if (obs.channelWidthMHz > 0) append("/${obs.channelWidthMHz}MHz")
                append("  ${obs.rssi}dBm")
                if (obs.rsrp != 0) append("  RSRP ${obs.rsrp}")
                if (obs.rsrq != 0) append("  RSRQ ${obs.rsrq}")
                if (obs.vendor != null) append("  ·  ${obs.vendor}")
                else if (obs.macRandomized) append("  ·  randomized MAC")
                if (obs.isReturning) append("  ·  seen before")
            }
            binding.sourceTag.text = obs.source.label
            binding.sourceTag.setBackgroundColor(obs.source.color)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW = 1
    }
}
