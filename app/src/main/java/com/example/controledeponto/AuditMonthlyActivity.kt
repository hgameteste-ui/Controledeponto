/*
 * Nome: AuditMonthlyActivity.kt
 * Versão: 4.0.0
 * Data: 13/02/2025
 * Hora: 11:45
 * Descrição: Tela de auditoria detalhada atualizada para o modelo único de intervalos.
 * Removidos campos legados e unificada a visualização da linha do tempo.
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
                val worked = dayWithIntervals.calculateTotalMinutes(isToday = day.date == LocalDate.now())
                
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes
                
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

        private fun formatDurationTimeline(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) String.format("%02dh%02d", h, m) else String.format("%02dm", m)
        }

        inner class ViewHolder(private val itemBinding: ItemAuditDayBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(item: AuditItem) {
                val dayWithIntervals = item.dayWithIntervals
                val day = dayWithIntervals.workDay
                val dayOfWeek = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
                val isToday = day.date == LocalDate.now()
                
                val statusSuffix = when {
                    day.isAbsence -> " - [FALTA]"
                    day.isHolidayOrOffDay -> " - [${day.holidayName ?: "FERIADO"}]"
                    else -> ""
                }
                
                itemBinding.tvDayDate.text = "${day.date.format(DateTimeFormatter.ofPattern("dd/MM"))} - $dayOfWeek$statusSuffix"
                val dateColor = when {
                    day.isAbsence -> Color.parseColor("#EF5350")
                    day.isHolidayOrOffDay -> Color.parseColor("#FFCA28")
                    else -> Color.WHITE
                }
                itemBinding.tvDayDate.setTextColor(dateColor)

                val ent = day.clockIn
                val sai = day.clockOut
                
                if (day.isAbsence) {
                    itemBinding.tvTimes.text = "Dia com falta sinalizada"
                } else if (day.isHolidayOrOffDay && ent == null) {
                    itemBinding.tvTimes.text = "Feriado/Folga: Meta Zero"
                } else if (ent != null) {
                    val timeline = StringBuilder()
                    val allBreaks = dayWithIntervals.intervals.map { it.startTime to (it.endTime ?: if (isToday) LocalTime.now() else it.startTime) }
                    
                    val validBreaks = allBreaks.filter { !it.first.isBefore(ent) }.sortedBy { it.first }
                    
                    timeline.append(ent.format(timeFormatter))
                    var lastPointer = ent
                    validBreaks.forEach { brk ->
                        if (!brk.first.isBefore(lastPointer)) {
                            timeline.append(" - ").append(brk.first.format(timeFormatter))
                            val dur = ChronoUnit.MINUTES.between(brk.first, brk.second).coerceAtLeast(0)
                            timeline.append(" intervalo ").append(formatDurationTimeline(dur))
                            timeline.append(" - ").append(brk.second.format(timeFormatter))
                            lastPointer = brk.second
                        }
                    }
                    
                    val inActiveBreak = dayWithIntervals.intervals.any { it.endTime == null }
                    val finalEnd = sai ?: if (isToday && !inActiveBreak) LocalTime.now().truncatedTo(ChronoUnit.MINUTES) else null
                    
                    if (finalEnd != null && !finalEnd.isBefore(lastPointer)) {
                        timeline.append(" - ").append(finalEnd.format(timeFormatter))
                    } else if (inActiveBreak) {
                        timeline.append(" - [EM PAUSA]")
                    } else if (sai == null && !isToday) {
                        timeline.append(" - [EM ABERTO]")
                    }
                    
                    itemBinding.tvTimes.text = timeline.toString()
                } else {
                    itemBinding.tvTimes.text = "Sem registros de horários"
                }

                itemBinding.tvIntervalsRegular.visibility = View.GONE
                itemBinding.tvIntervalsPauses.visibility = View.GONE

                val worked = dayWithIntervals.calculateTotalMinutes(isToday = isToday)
                val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
                val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes

                itemBinding.tvDayGoal.text = when {
                    day.isAbsence -> "Meta: ${formatDuration(dailyGoalMinutes)} (Falta)"
                    day.isHolidayOrOffDay -> "Meta: 00h 00m - ${day.holidayName ?: "Feriado/Folga"}"
                    else -> String.format("Meta: %02dh %02dm", effectiveGoal / 60, effectiveGoal % 60)
                }

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
