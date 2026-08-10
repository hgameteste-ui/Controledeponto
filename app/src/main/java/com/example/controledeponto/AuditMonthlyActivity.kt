/*
 * Nome: AuditMonthlyActivity.kt
 * Versão: 2.8.0
 * Data: 12/02/2025
 * Hora: 16:30
 * Descrição: Tela de auditoria detalhada que lista todos os registros do mês.
 * Atualizada para exibir o tempo individual de cada intervalo e o total por categoria.
 * 
 * Histórico de Modificações:
 * 24/05/2024 23:30 - Alterada para usar WorkDayWithIntervals, garantindo contabilização correta de pausas nos totais.
 * 12/02/2025 15:10 - Adicionada chamada ao updateAuditUi() no onCreate para carregar dados iniciais.
 * 12/02/2025 15:30 - Melhorada a exibição de intervalos e pausas no adapter.
 * 12/02/2025 16:00 - Adicionado cálculo e exibição do tempo total por categoria de intervalo.
 * 12/02/2025 16:30 - Refinada a formatação de duração para incluir tempo individual e total formatado.
 */

package com.example.controledeponto

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.example.controledeponto.databinding.ActivityAuditMonthlyBinding
import com.example.controledeponto.databinding.ItemAuditDayBinding
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class AuditMonthlyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuditMonthlyBinding
    private val viewModel: WorkViewModel by viewModels()
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale("pt", "BR"))
    private var auditDate: LocalDate = LocalDate.now()

    data class AuditItem(val dayWithIntervals: WorkDayWithIntervals, val runningBalance: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuditMonthlyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        auditDate = viewModel.selectedDate.value ?: LocalDate.now()

        setupRecyclerView()
        setupObservers()
        setupListeners()
        
        updateAuditUi()
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
        binding.tvAuditMonth.text = "Auditoria: ${auditDate.format(monthFormatter).replaceFirstChar { it.uppercase() }}"
        viewModel.changeAuditMonth(auditDate.year, auditDate.monthValue)
    }

    private fun setupRecyclerView() {
        binding.rvAudit.adapter = AuditAdapter { day ->
            viewModel.setDate(day.date)
            finish()
        }
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            auditDate = date
            binding.tvAuditMonth.text = "Auditoria: ${date.format(monthFormatter).replaceFirstChar { it.uppercase() }}"
        }

        viewModel.monthlyWorkDaysWithIntervals.observe(this) { list ->
            (binding.rvAudit.adapter as AuditAdapter).submitList(list)
        }
    }

    inner class AuditAdapter(private val onClick: (WorkDay) -> Unit) : RecyclerView.Adapter<AuditAdapter.ViewHolder>() {
        
        private var items = listOf<AuditItem>()
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun submitList(newList: List<WorkDayWithIntervals>) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AuditMonthlyActivity)
            val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
            val dailyGoalMinutes = workHours * 60

            var accumulator = 0L
            items = newList.map { dayWithIntervals ->
                val day = dayWithIntervals.workDay
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes
                val worked = dayWithIntervals.calculateTotalMinutes(isToday = day.date == LocalDate.now())
                accumulator += (worked - effectiveGoal)
                AuditItem(dayWithIntervals, accumulator)
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

        private fun formatDuration(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) String.format("%02dh %02dm", h, m) else String.format("%02dm", m)
        }

        inner class ViewHolder(private val itemBinding: ItemAuditDayBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(item: AuditItem) {
                val dayWithIntervals = item.dayWithIntervals
                val day = dayWithIntervals.workDay
                val dayOfWeek = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
                itemBinding.tvDayDate.text = "${day.date.format(DateTimeFormatter.ofPattern("dd/MM"))} - $dayOfWeek"

                val ent = day.clockIn?.format(timeFormatter) ?: "--:--"
                val sai = day.clockOut?.format(timeFormatter) ?: "--:--"
                itemBinding.tvTimes.text = "Entrada: $ent | Saída: $sai"

                // Lógica para Intervalos Regulares (Legado + Novos do tipo LUNCH)
                val regularDetails = mutableListOf<String>()
                var regularTotalMinutes = 0L
                
                // Intervalo legado (breakStart/End)
                if (day.breakStart != null) {
                    val bStart = day.breakStart
                    val bEnd = day.breakEnd ?: (if (day.date == LocalDate.now()) LocalTime.now() else bStart)
                    val diff = ChronoUnit.MINUTES.between(bStart, bEnd).coerceAtLeast(0)
                    regularTotalMinutes += diff
                    regularDetails.add("${bStart.format(timeFormatter)} - ${bEnd.format(timeFormatter)} (${formatDuration(diff)})")
                }
                
                // Novos intervalos marcados como Almoço
                val lunchIntervals = dayWithIntervals.intervals.filter { it.type == IntervalType.LUNCH }
                lunchIntervals.forEach { interval ->
                    val iStart = interval.startTime
                    val iEnd = interval.endTime ?: (if (day.date == LocalDate.now()) LocalTime.now() else iStart)
                    val diff = ChronoUnit.MINUTES.between(iStart, iEnd).coerceAtLeast(0)
                    regularTotalMinutes += diff
                    regularDetails.add("${iStart.format(timeFormatter)} - ${iEnd.format(timeFormatter)} (${formatDuration(diff)})")
                }

                if (regularDetails.isEmpty()) {
                    itemBinding.tvIntervalsRegular.visibility = View.GONE
                } else {
                    itemBinding.tvIntervalsRegular.visibility = View.VISIBLE
                    itemBinding.tvIntervalsRegular.text = "Regulares: ${regularDetails.joinToString(" | ")} - Total: ${formatDuration(regularTotalMinutes)}"
                }

                // Lógica para Pausas (Novos intervalos que NÃO são LUNCH)
                val otherIntervals = dayWithIntervals.intervals.filter { it.type != IntervalType.LUNCH }
                if (otherIntervals.isEmpty()) {
                    itemBinding.tvIntervalsPauses.visibility = View.GONE
                } else {
                    itemBinding.tvIntervalsPauses.visibility = View.VISIBLE
                    var pauseTotalMinutes = 0L
                    val pausesDetails = otherIntervals.map { interval ->
                        val iStart = interval.startTime
                        val iEnd = interval.endTime ?: (if (day.date == LocalDate.now()) LocalTime.now() else iStart)
                        val diff = ChronoUnit.MINUTES.between(iStart, iEnd).coerceAtLeast(0)
                        pauseTotalMinutes += diff
                        "${iStart.format(timeFormatter)}-${iEnd.format(timeFormatter)} (${formatDuration(diff)})"
                    }
                    itemBinding.tvIntervalsPauses.text = "Pausas: ${pausesDetails.joinToString(", ")} - Total: ${formatDuration(pauseTotalMinutes)}"
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
                val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
                val dailyGoalMinutes = workHours * 60

                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes

                itemBinding.tvDayGoal.text = if (day.isHolidayOrOffDay) "Meta: 00h 00m - ${day.holidayName ?: "Folga/Feriado"}" 
                                             else String.format("Meta: %02dh %02dm", effectiveGoal / 60, effectiveGoal % 60)

                val worked = dayWithIntervals.calculateTotalMinutes(isToday = day.date == LocalDate.now())
                val balance = worked - effectiveGoal
                val absBalance = Math.abs(balance)
                val sign = if (balance >= 0) "+" else "-"
                itemBinding.tvDayBalance.text = String.format("%s%02dh %02dm", sign, absBalance / 60, absBalance % 60)
                itemBinding.tvDayBalance.setTextColor(if (balance >= 0) Color.parseColor("#66BB6A") else Color.parseColor("#FFA726"))

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
