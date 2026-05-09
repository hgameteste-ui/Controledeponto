package com.example.controledeponto

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ItemHistoryBinding
import java.time.Duration
import java.time.format.DateTimeFormatter

class HistoryAdapter : ListAdapter<WorkDay, HistoryAdapter.ViewHolder>(DiffCallback) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkDay) {
            binding.tvHistoryDate.text = item.date.format(dateFormatter)
            binding.tvHistoryTimes.text = "Entrada: ${item.clockIn?.format(timeFormatter) ?: "--"} | Saída: ${item.clockOut?.format(timeFormatter) ?: "--"}"
            
            val totalMinutes = calculateMinutes(item)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            binding.tvHistoryTotal.text = String.format("Total: %02dh %02dm", hours, minutes)
        }

        private fun calculateMinutes(item: WorkDay): Long {
            var total = 0L
            if (item.clockIn != null && item.breakStart != null) {
                total += Duration.between(item.clockIn, item.breakStart).toMinutes()
            }
            if (item.breakEnd != null && item.clockOut != null) {
                total += Duration.between(item.breakEnd, item.clockOut).toMinutes()
            }
            return total
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WorkDay>() {
        override fun areItemsTheSame(oldItem: WorkDay, newItem: WorkDay) = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: WorkDay, newItem: WorkDay) = oldItem == newItem
    }
}
