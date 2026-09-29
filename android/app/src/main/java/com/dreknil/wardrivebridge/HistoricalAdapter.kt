package com.dreknil.wardrivebridge

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dreknil.wardrivebridge.databinding.ItemObservationBinding

/** Same header/row split and collapse behavior as ObservationAdapter, but
 * over merged historical points instead of the live per-run feed. Tapping a
 * row shows the same detail dialog a map marker tap does. */
class HistoricalAdapter(
    private val onHeaderClick: (String) -> Unit,
    private val onRowClick: (HistoricalPoint) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<HistoricalFeedItem> = emptyList()

    fun submitList(newItems: List<HistoricalFeedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HistoricalFeedItem.Header -> VIEW_TYPE_HEADER
        is HistoricalFeedItem.Row -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false) as TextView, onHeaderClick)
        } else {
            RowViewHolder(ItemObservationBinding.inflate(inflater, parent, false), onRowClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoricalFeedItem.Header -> (holder as HeaderViewHolder).bind(item)
            is HistoricalFeedItem.Row -> (holder as RowViewHolder).bind(item.point)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(
        private val view: TextView,
        onHeaderClick: (String) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private var boundLabel: String? = null

        init {
            view.setOnClickListener { boundLabel?.let(onHeaderClick) }
        }

        fun bind(header: HistoricalFeedItem.Header) {
            boundLabel = header.groupLabel
            val arrow = if (header.expanded) "▼" else "▶"
            view.text = "$arrow  ${header.groupLabel.uppercase()}  ·  ${header.count}"
        }
    }

    class RowViewHolder(
        private val binding: ItemObservationBinding,
        private val onRowClick: (HistoricalPoint) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bound: HistoricalPoint? = null

        init {
            binding.root.setOnClickListener { bound?.let(onRowClick) }
        }

        fun bind(point: HistoricalPoint) {
            bound = point
            val name = point.label.ifBlank { "(hidden)" }
            binding.title.text = "$name  ·  ${point.mac}"
            binding.subtitle.text = buildString {
                append(point.authType.ifBlank { point.type })
                if (point.channel > 0) append("  ch${point.channel}")
                append("  ${point.rssi}dBm")
            }
            binding.sourceTag.text = point.type
            binding.sourceTag.setBackgroundColor(historicalTypeColor(point.type))
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW = 1
    }
}
