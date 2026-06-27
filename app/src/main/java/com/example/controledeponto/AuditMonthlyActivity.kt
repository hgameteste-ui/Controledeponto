/**
 * Nome do Arquivo: AuditMonthlyActivity.kt
 * Pacote: com.example.controledeponto
 * Projeto: Controle de Ponto Eletrônico
 *
 * Descrição:
 * Tela de auditoria detalhada que lista todos os registros do mês selecionado.
 * Permite identificar erros de lançamento e inconsistências que afetam o saldo.
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 2.3.2    Jun/2026    Walter R. C.    Ajuste de clipping e padding de acessibilidade do RecyclerView.
 * 2.3.0    Jun/2026    Walter R. C.    Implementação da soma corrente (Running Total) no saldo mensal.
 * 2.1.6    Jun/2026    Walter R. C.    Correção do bug de clique nos seletores de mês e reatividade.
 * 2.1.0    Jun/2026    Walter R. C.    Criação da tela de auditoria mensal detalhada.
 */

package com.example.controledeponto

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ActivityAuditMonthlyBinding
import com.example.controledeponto.databinding.ItemAuditDayBinding
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class AuditMonthlyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuditMonthlyBinding
    private val viewModel: WorkViewModel by viewModels()
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale("pt", "BR"))
    private var auditDate: LocalDate = LocalDate.now()

    // Wrapper movido para fora do Adapter para evitar erro de aninhamento em inner classes
    data class AuditItem(val day: WorkDay, val runningBalance: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuditMonthlyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Inicializa a data local com a data do ViewModel
        auditDate = viewModel.selectedDate.value ?: LocalDate.now()

        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnPrevMonth.setOnClickListener {
            auditDate = auditDate.minusMonths(1)
            updateAuditUi()
        }

        binding.btnNextMonth.setOnClickListener {
            auditDate = auditDate.plusMonths(1)
            updateAuditUi()
        }
    }

    private fun updateAuditUi() {
        // Atualiza o texto imediatamente para feedback visual
        binding.tvAuditMonth.text = "Auditoria: ${auditDate.format(monthFormatter).replaceFirstChar { it.uppercase() }}"
        
        // Notifica o ViewModel para carregar os dados do novo período
        viewModel.changeAuditMonth(auditDate.year, auditDate.monthValue)
    }

    private fun setupRecyclerView() {
        binding.rvAudit.adapter = AuditAdapter { workDay ->
            viewModel.setDate(workDay.date)
            finish()
        }
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            auditDate = date
            binding.tvAuditMonth.text = "Auditoria: ${date.format(monthFormatter).replaceFirstChar { it.uppercase() }}"
        }

        viewModel.monthlyWorkDays.observe(this) { list ->
            (binding.rvAudit.adapter as AuditAdapter).submitList(list)
        }
    }

    inner class AuditAdapter(private val onClick: (WorkDay) -> Unit) : RecyclerView.Adapter<AuditAdapter.ViewHolder>() {
        
        private var items = listOf<AuditItem>()
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun submitList(newList: List<WorkDay>) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AuditMonthlyActivity)
            val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
            val dailyGoalMinutes = workHours * 60

            var accumulator = 0L
            items = newList.map { day ->
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes
                val worked = day.calculateTotalMinutes()
                accumulator += (worked - effectiveGoal)
                AuditItem(day, accumulator)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAuditDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val itemBinding: ItemAuditDayBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(item: AuditItem) {
                val day = item.day
                val dayOfWeek = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
                itemBinding.tvDayDate.text = "${day.date.format(DateTimeFormatter.ofPattern("dd/MM"))} - $dayOfWeek"

                val ent = day.clockIn?.format(timeFormatter) ?: "--:--"
                val i1 = day.breakStart?.format(timeFormatter) ?: "--:--"
                val i2 = day.breakEnd?.format(timeFormatter) ?: "--:--"
                val sai = day.clockOut?.format(timeFormatter) ?: "--:--"
                itemBinding.tvTimes.text = "Ent: $ent | Int: $i1 - $i2 | Saí: $sai"

                val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
                val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
                val dailyGoalMinutes = workHours * 60

                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes

                itemBinding.tvDayGoal.text = if (day.isHolidayOrOffDay) "Meta: 00h 00m - ${day.holidayName ?: "Folga/Feriado"}" 
                                             else String.format("Meta: %02dh %02dm", effectiveGoal / 60, effectiveGoal % 60)

                // Saldo do dia
                val worked = day.calculateTotalMinutes()
                val balance = worked - effectiveGoal
                val absBalance = Math.abs(balance)
                val sign = if (balance >= 0) "+" else "-"
                itemBinding.tvDayBalance.text = String.format("%s%02dh %02dm", sign, absBalance / 60, absBalance % 60)
                itemBinding.tvDayBalance.setTextColor(if (balance >= 0) Color.parseColor("#66BB6A") else Color.parseColor("#FFA726"))

                // Saldo Acumulado (Running Total)
                val acc = item.runningBalance
                val absAcc = Math.abs(acc)
                val accSign = if (acc >= 0) "+" else "-"
                itemBinding.tvAccumulatedRunningBalance.text = String.format("Acumulado: %s%02dh %02dm", accSign, absAcc / 60, absAcc % 60)
                itemBinding.tvAccumulatedRunningBalance.setTextColor(if (acc >= 0) Color.parseColor("#81C784") else Color.parseColor("#FFA726"))

                itemBinding.root.setOnClickListener { onClick(day) }
            }
        }
    }
}
