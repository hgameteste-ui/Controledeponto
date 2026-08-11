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
 * 12/02/2025 18:45 - Atualizada para usar saveHoliday/removeHoliday com suporte a nomes customizados.
 */

package com.example.controledeponto

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ActivityHolidaysConfigBinding
import com.google.android.material.snackbar.Snackbar
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
            onEdit = { showEditHolidayFlow(it) }
        )
        binding.rvHolidays.adapter = adapter

        viewModel.holidaysList.observe(this) { holidays ->
            adapter.submitList(holidays)
        }

        viewModel.importStatus.observe(this) { status ->
            status?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearImportStatus()
            }
        }

        binding.fabAddHoliday.setOnClickListener {
            showEditHolidayFlow(null)
        }
    }

    private fun showEditHolidayFlow(existing: WorkDay?) {
        val initialDate = existing?.date ?: LocalDate.now()
        
        // 1. Seleciona a Data
        DatePickerDialog(this, { _, year, month, day ->
            val selectedDate = LocalDate.of(year, month + 1, day)
            
            // 2. Após a data, solicita o Nome
            showNameInputDialog(selectedDate, existing)
            
        }, initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth).show()
    }

    private fun showNameInputDialog(selectedDate: LocalDate, existing: WorkDay?) {
        val input = EditText(this)
        input.hint = "Ex: Feriado Local, Folga, etc."
        if (existing != null) input.setText(existing.holidayName)

        AlertDialog.Builder(this)
            .setTitle("Nome do Feriado/Folga")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().ifBlank { "Folga Manual" }
                viewModel.saveHoliday(selectedDate, name, existing?.date)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteConfirmation(workDay: WorkDay) {
        AlertDialog.Builder(this)
            .setTitle("Remover Feriado")
            .setMessage("Deseja realmente remover a marcação de feriado desta data?")
            .setPositiveButton("Remover") { _, _ ->
                viewModel.removeHoliday(workDay)
            }
            .setNegativeButton("Cancelar", null)
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
            
            val dayOfWeekStr = item.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
            holder.tvDate.text = String.format("%s, %s", dayOfWeekStr, item.date.format(dateFormatter))
            
            holder.tvName.text = item.holidayName ?: "Folga Manual / Feriado"

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
