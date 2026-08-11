/*
 * Nome: WorkIntervalAdapter.kt
 * Versão: 1.4.0
 * Data: 13/02/2025
 * Hora: 10:30
 * Descrição: Adapter atualizado para exibir a duração entre os horários no formato: "12:00 - intervalo 01h30 - 13:30".
 */

package com.example.controledeponto

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ItemIntervalBinding
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class WorkIntervalAdapter(
    private val onIntervalClick: (WorkInterval) -> Unit
) : ListAdapter<WorkInterval, WorkIntervalAdapter.IntervalViewHolder>(IntervalDiffCallback()) {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntervalViewHolder {
        val binding = ItemIntervalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IntervalViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IntervalViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class IntervalViewHolder(private val binding: ItemIntervalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkInterval) {
            binding.root.setOnClickListener { onIntervalClick(item) }

            binding.tvIntervalType.text = when (item.type) {
                IntervalType.LUNCH -> "ALMOÇO"
                IntervalType.BREAK -> "PAUSA"
                IntervalType.OTHER -> "OUTRO"
            }

            val startStr = item.startTime.format(timeFormatter)
            val endStr = item.endTime?.format(timeFormatter) ?: "--:--"
            
            val end = item.endTime ?: LocalTime.now()
            val durationMinutes = ChronoUnit.MINUTES.between(item.startTime, end).coerceAtLeast(0)
            
            // Formato: 12:00 - intervalo 01h30 - 13:30
            binding.tvIntervalTimes.text = String.format(Locale("pt", "BR"), "%s - intervalo %s - %s", 
                startStr, formatDurationTimeline(durationMinutes), endStr)

            binding.tvIntervalDuration.visibility = android.view.View.GONE
        }

        private fun formatDurationTimeline(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) String.format(Locale("pt", "BR"), "%02dh%02d", h, m) else String.format(Locale("pt", "BR"), "%02dm", m)
        }
    }

    class IntervalDiffCallback : DiffUtil.ItemCallback<WorkInterval>() {
        override fun areItemsTheSame(oldItem: WorkInterval, newItem: WorkInterval): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: WorkInterval, newItem: WorkInterval): Boolean = oldItem == newItem
    }
}
