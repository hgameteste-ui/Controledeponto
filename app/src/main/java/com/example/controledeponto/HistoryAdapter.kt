package com.example.controledeponto

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ItemHistoryBinding
import java.time.format.DateTimeFormatter

class HistoryAdapter(private val onItemClicked: (WorkDay) -> Unit) : ListAdapter<WorkDay, HistoryAdapter.ViewHolder>(DiffCallback) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onItemClicked(item) }
    }

    inner class ViewHolder(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkDay) {
            binding.tvHistoryDate.text = item.date.format(dateFormatter)
            binding.tvHistoryTimes.text = "E: ${item.clockIn?.format(timeFormatter) ?: "--"} | S: ${item.clockOut?.format(timeFormatter) ?: "--"}"
            
            // Usa a função de cálculo centralizada do WorkDay
            val totalMinutes = item.calculateTotalMinutes(isToday = false)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            binding.tvHistoryTotal.text = String.format("%02dh %02dm", hours, minutes)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WorkDay>() {
        override fun areItemsTheSame(oldItem: WorkDay, newItem: WorkDay) = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: WorkDay, newItem: WorkDay) = oldItem == newItem
    }
}
