package com.dreknil.wardrivebridge

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dreknil.wardrivebridge.databinding.ItemLogFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tap a row to share that run's materialized CSV, long-press for more
 * options (view on map, delete). In selection mode (see MainActivity's
 * selectModeButton), tap instead toggles whether the row is selected,
 * highlighted so the current selection is visible at a glance. */
class LogsAdapter(
    private val onClick: (RunSummary) -> Unit,
    private val onLongClick: (RunSummary) -> Unit,
    private val onUploadClick: (RunSummary) -> Unit,
) : RecyclerView.Adapter<LogsAdapter.ViewHolder>() {
    private var runs: List<RunSummary> = emptyList()
    private var selectionMode = false
    private var selectedRunIds: Set<Long> = emptySet()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)

    fun submitList(newRuns: List<RunSummary>) {
        runs = newRuns
        notifyDataSetChanged()
    }

    fun setSelectionState(mode: Boolean, selected: Set<Long>) {
        selectionMode = mode
        selectedRunIds = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onClick, onLongClick, onUploadClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val run = runs[position]
        holder.bind(run, dateFormat, selectionMode, run.id in selectedRunIds)
    }

    override fun getItemCount(): Int = runs.size

    class ViewHolder(
        private val binding: ItemLogFileBinding,
        private val onClick: (RunSummary) -> Unit,
        private val onLongClick: (RunSummary) -> Unit,
        private val onUploadClick: (RunSummary) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bound: RunSummary? = null

        init {
            binding.root.setOnClickListener { bound?.let(onClick) }
            binding.root.setOnLongClickListener { bound?.let(onLongClick); true }
            binding.logFileUploadButton.setOnClickListener { bound?.let(onUploadClick) }
        }

        fun bind(run: RunSummary, dateFormat: SimpleDateFormat, selectionMode: Boolean, selected: Boolean) {
            bound = run
            // Square-bracket checkbox + system-log-style row, per the
            // tactical cyberdeck spec ("run_20260927_194600 | 0 pts | STATUS:
            // OK") - STATUS here reflects this device's own upload state
            // (SENT/PENDING), the one piece of "is this log okay" info this
            // tab actually tracks.
            val checkPrefix = if (selectionMode) (if (selected) "[x] " else "[ ] ") else ""
            binding.logFileName.text = checkPrefix + (run.note.ifBlank { run.label }).uppercase()
            val status = if (run.uploadedAt != null) "STATUS: SENT" else "STATUS: PENDING"
            val meta = "${dateFormat.format(Date(run.startedAtMs))} | ${run.count} PTS | $status"
            binding.logFileMeta.text = if (run.note.isNotBlank()) "${run.label} | $meta" else meta
            binding.root.setBackgroundColor(if (selected) Color.parseColor("#332D9CE0") else Color.TRANSPARENT)

            // In selection mode, the row itself is the whole point of the tap
            // target (toggling selection) - hide the per-row upload action so
            // it can't fire by accident from a tap meant for selection.
            val uploaded = run.uploadedAt != null
            binding.logFileUploadedMark.visibility = if (!selectionMode && uploaded) View.VISIBLE else View.GONE
            binding.logFileUploadButton.visibility = if (!selectionMode && !uploaded) View.VISIBLE else View.GONE
        }
    }
}
