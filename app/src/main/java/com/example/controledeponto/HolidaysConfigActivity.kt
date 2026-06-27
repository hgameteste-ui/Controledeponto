/**
 * Nome do Arquivo: HolidaysConfigActivity.kt
 * Pacote: com.example.controledeponto
 * Projeto: Controle de Ponto Eletrônico
 *
 * Descrição:
 * Tela de configuração para gerenciamento de feriados e folgas. Permite visualizar,
 * adicionar, editar e excluir datas marcadas com a flag [isHolidayOrOffDay].
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 1.9.7    Jun/2026    Walter R. C.    Inclusão do dia da semana localizado (pt-BR) na listagem.
 * 1.9.6    Jun/2026    Walter R. C.    Ordenação cronológica crescente (ASC) e exibição 
 *                                      da descrição oficial do feriado.
 * 1.9.0    Jun/2026    Walter R. C.    Criação inicial da tela de gerenciamento de feriados.
 */

package com.example.controledeponto

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ActivityHolidaysConfigBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

class HolidaysConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHolidaysConfigBinding
    private val viewModel: WorkViewModel by viewModels()
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHolidaysConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = HolidayAdapter(
            onDelete = { showDeleteConfirmation(it) },
            onEdit = { showDatePicker(it) }
        )
        binding.rvHolidays.adapter = adapter

        viewModel.holidaysList.observe(this) { holidays ->
            adapter.submitList(holidays)
        }

        binding.fabAddHoliday.setOnClickListener {
            showDatePicker(null)
        }
    }

    private fun showDatePicker(existing: WorkDay?) {
        val initialDate = existing?.date ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, day ->
            val selectedDate = LocalDate.of(year, month + 1, day)
            val updated = existing?.copy(date = selectedDate) ?: WorkDay(date = selectedDate, isHolidayOrOffDay = true)
            viewModel.updateWorkDay(updated)
        }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
    }

    private fun showDeleteConfirmation(workDay: WorkDay) {
        AlertDialog.Builder(this)
            .setTitle("Remover Feriado")
            .setMessage("Deseja realmente remover esta marcação?")
            .setPositiveButton("Sim") { _, _ ->
                viewModel.updateWorkDay(workDay.copy(isHolidayOrOffDay = false))
            }
            .setNegativeButton("Não", null)
            .show()
    }

    inner class HolidayAdapter(
        private val onDelete: (WorkDay) -> Unit,
        private val onEdit: (WorkDay) -> Unit
    ) : RecyclerView.Adapter<HolidayAdapter.ViewHolder>() {

        private var list: List<WorkDay> = emptyList()

        fun submitList(newList: List<WorkDay>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_holiday, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            
            // Extração do dia da semana formatado
            val dayOfWeekStr = item.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
            
            // Formatação: "Sexta-feira, 01/05/2026"
            holder.tvDate.text = String.format("%s, %s", dayOfWeekStr, item.date.format(dateFormatter))
            
            holder.tvName.text = if (!item.holidayName.isNullOrBlank()) {
                item.holidayName
            } else {
                "Folga Manual / Feriado"
            }

            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.itemView.setOnClickListener { onEdit(item) }
        }

        override fun getItemCount() = list.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(R.id.tvHolidayDate)
            val tvName: TextView = view.findViewById(R.id.tvHolidayName)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteHoliday)
        }
    }
}
