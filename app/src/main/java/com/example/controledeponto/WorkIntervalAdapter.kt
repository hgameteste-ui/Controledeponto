/*
 * Nome: WorkIntervalAdapter.kt
 * Versão: 1.1.0
 * Data: 24/05/2024
 * Hora: 20:00
 * Descrição: Adapter para o RecyclerView que exibe a lista de intervalos (pausas) de um dia.
 * 
 * Histórico de Modificações:
 * 24/05/2024 15:00 - Criação inicial com suporte a múltiplos intervalos.
 * 24/05/2024 20:00 - Adicionado listener de clique para permitir a exclusão de intervalos.
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
            val context = binding.root.context
            
            binding.root.setOnClickListener { onIntervalClick(item) }

            binding.tvIntervalType.text = when (item.type) {
                IntervalType.LUNCH -> "ALMOÇO"
                IntervalType.BREAK -> "PAUSA"
                IntervalType.OTHER -> "OUTRO"
            }

            val startStr = item.startTime.format(timeFormatter)
            val endStr = item.endTime?.format(timeFormatter) ?: "--:--"
            binding.tvIntervalTimes.text = context.getString(R.string.interval_times_format, startStr, endStr)

            val end = item.endTime ?: LocalTime.now()
            val durationMinutes = ChronoUnit.MINUTES.between(item.startTime, end)
            binding.tvIntervalDuration.text = formatDuration(durationMinutes)
        }

        private fun formatDuration(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return String.format(Locale.getDefault(), "%02dh %02dm", h, m)
        }
    }

    class IntervalDiffCallback : DiffUtil.ItemCallback<WorkInterval>() {
        override fun areItemsTheSame(oldItem: WorkInterval, newItem: WorkInterval): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: WorkInterval, newItem: WorkInterval): Boolean = oldItem == newItem
    }
}
