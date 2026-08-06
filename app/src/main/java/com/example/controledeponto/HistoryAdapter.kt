/*
 * Nome: HistoryAdapter.kt
 * Versão: 1.1.0
 * Data: 24/05/2024
 * Hora: 22:30
 * Descrição: Adapter para o histórico de registros, atualizado para exibir o tempo total considerando os intervalos reais.
 * 
 * Histórico de Modificações:
 * 24/05/2024 22:30 - Alterado para usar WorkDayWithIntervals visando precisão nos cálculos de horas trabalhadas.
 */

package com.example.controledeponto

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ItemHistoryBinding
import java.time.format.DateTimeFormatter
import java.util.Locale

class HistoryAdapter(private val onItemClicked: (WorkDayWithIntervals) -> Unit) : ListAdapter<WorkDayWithIntervals, HistoryAdapter.ViewHolder>(DiffCallback) {

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
        fun bind(item: WorkDayWithIntervals) {
            val workDay = item.workDay
            binding.tvHistoryDate.text = workDay.date.format(dateFormatter)
            binding.tvHistoryTimes.text = "E: ${workDay.clockIn?.format(timeFormatter) ?: "--"} | S: ${workDay.clockOut?.format(timeFormatter) ?: "--"}"
            
            // Calcula o tempo total real usando os intervalos associados
            val totalMinutes = item.calculateTotalMinutes(isToday = false)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            binding.tvHistoryTotal.text = String.format(Locale.getDefault(), "%02dh %02dm", hours, minutes)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WorkDayWithIntervals>() {
        override fun areItemsTheSame(oldItem: WorkDayWithIntervals, newItem: WorkDayWithIntervals) = 
            oldItem.workDay.date == newItem.workDay.date
        
        override fun areContentsTheSame(oldItem: WorkDayWithIntervals, newItem: WorkDayWithIntervals) = 
            oldItem == newItem
    }
}
