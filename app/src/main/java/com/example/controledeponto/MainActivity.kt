package com.example.controledeponto

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.example.controledeponto.databinding.ActivityMainBinding
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: WorkViewModel by viewModels()
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Locale("pt", "BR"))
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val backupCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(it) }
    }

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupObservers()
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnPunch.setOnClickListener {
            viewModel.punchClock()
        }

        binding.tvDate.setOnClickListener {
            showDatePicker()
        }

        binding.btnPreviousDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.minusDays(1))
        }

        binding.btnNextDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.plusDays(1))
        }

        binding.btnToday.setOnClickListener {
            viewModel.setDate(LocalDate.now())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_backup -> {
                val date = viewModel.selectedDate.value ?: LocalDate.now()
                val fileName = "backup_ponto_${date.monthValue}_${date.year}.csv"
                backupCsvLauncher.launch(fileName)
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_import -> {
                importCsvLauncher.launch("*/*")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            // Formata a data com o dia da semana e capitaliza a primeira letra
            val formattedDate = date.format(dateFormatter)
            binding.tvDate.text = formattedDate.replaceFirstChar { it.uppercase() }
            
            if (date == LocalDate.now()) {
                binding.tvDate.setTextColor(resources.getColor(R.color.purple_500, theme))
            } else {
                binding.tvDate.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
            }
            updateToolbarSummary()
        }

        viewModel.selectedWorkDay.observe(this) { workDay ->
            // Atualiza apenas os valores das horas, os rótulos estão fixos no XML para alinhamento
            binding.tvClockIn.text = workDay?.clockIn?.format(timeFormatter) ?: "--:--"
            binding.tvBreakStart.text = workDay?.breakStart?.format(timeFormatter) ?: "--:--"
            binding.tvBreakEnd.text = workDay?.breakEnd?.format(timeFormatter) ?: "--:--"
            binding.tvClockOut.text = workDay?.clockOut?.format(timeFormatter) ?: "--:--"

            updateStats(workDay)
            updateButtonUI(workDay)
            setupManualEdits(workDay)
        }

        viewModel.monthlyBalanceMinutes.observe(this) { updateToolbarSummary() }

        // Observa os detalhes dos meses anteriores para preencher a lista acima do progresso
        viewModel.quarterlyMonthlyOvertime.observe(this) { monthlyList ->
            binding.layoutQuarterlyMonths.removeAllViews()
            
            // Re-adiciona o título
            val titleView = TextView(this).apply {
                text = "EXTRAS NO TRIMESTRE"
                setTextColor(resources.getColor(android.R.color.holo_blue_dark, theme))
                textSize = 11f
                setPadding(0, 0, 0, 8)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            binding.layoutQuarterlyMonths.addView(titleView)

            var totalMinutes = 0L
            monthlyList.forEach { (month, minutes) ->
                totalMinutes += minutes
                val hours = minutes / 60
                val mins = minutes % 60
                val textView = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%s: %02dh %02dm", month, hours, mins)
                    textSize = 14f
                    setTextColor(resources.getColor(android.R.color.black, theme))
                    setPadding(0, 4, 0, 4)
                }
                binding.layoutQuarterlyMonths.addView(textView)
            }

            if (monthlyList.isNotEmpty()) {
                // Linha separadora
                val separator = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                        setMargins(0, 8, 0, 8)
                    }
                    setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
                }
                binding.layoutQuarterlyMonths.addView(separator)

                // Totalizador
                val totalHours = totalMinutes / 60
                val totalMins = totalMinutes % 60
                val totalTextView = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "TOTAL: %02dh %02dm", totalHours, totalMins)
                    textSize = 15f
                    setTextColor(resources.getColor(android.R.color.holo_blue_dark, theme))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 4, 0, 4)
                }
                binding.layoutQuarterlyMonths.addView(totalTextView)
            }
        }

        // Observa o total de horas extras para atualizar o card de PROGRESSO MENSAL e a Toolbar
        viewModel.monthlyOvertimeMinutes.observe(this) { overtimeMinutes ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
            val goalMinutes = monthlyGoalHours * 60L

            val hours = overtimeMinutes / 60
            val mins = overtimeMinutes % 60
            val totalStr = String.format(Locale.getDefault(), "%02dh %02dm", hours, mins)
            
            // Card de progresso mensal agora mostra o TOTAL DE EXTRAS acumulado no mês
            binding.tvMonthlyTotal.text = totalStr
            binding.progressMonthly.max = goalMinutes.toInt()
            binding.progressMonthly.progress = overtimeMinutes.toInt().coerceAtMost(goalMinutes.toInt())

            val remainingMinutes = (goalMinutes - overtimeMinutes).coerceAtLeast(0)
            if (remainingMinutes > 0) {
                binding.tvMonthlyRemaining.text = String.format(Locale.getDefault(), "Faltam %dh %02dm para a meta de %dh", remainingMinutes / 60, remainingMinutes % 60, monthlyGoalHours)
            } else {
                binding.tvMonthlyRemaining.text = "Meta mensal batida! 🎉"
            }
            
            updateToolbarSummary()
        }

        // Observa a sugestão de horas EXTRAS diárias para atingir a meta
        viewModel.suggestedDailyOvertimeMinutes.observe(this) { minutes ->
            val hours = minutes / 60
            val mins = minutes % 60
            binding.tvSuggestedDaily.text = String.format(Locale.getDefault(), 
                "Sugestão de extras/dia: %02dh %02dm", hours, mins)
        }

        // Observa a extrapolação (projeção) de extras para o fim do mês
        viewModel.extrapolatedOvertimeMinutes.observe(this) { minutes ->
            val absMinutes = Math.abs(minutes)
            val hours = absMinutes / 60
            val mins = absMinutes % 60
            val sign = if (minutes >= 0) "+" else "-"
            binding.tvExtrapolatedOvertime.text = String.format(Locale.getDefault(), 
                "Projeção final do mês: %s%02dh %02dm", sign, hours, mins)
        }

        // Atualiza a contagem de dias úteis (Total e Restantes)
        viewModel.monthlyBusinessDays.observe(this) { total ->
            val remaining = viewModel.remainingBusinessDays.value ?: 0
            binding.tvMonthlyBusinessDays.text = "Dias úteis: $total | Restantes: $remaining"
        }
        
        viewModel.remainingBusinessDays.observe(this) { remaining ->
            val total = viewModel.monthlyBusinessDays.value ?: 0
            binding.tvMonthlyBusinessDays.text = "Dias úteis: $total | Restantes: $remaining"
        }

        // Observa o status da importação para mostrar erros ou sucesso em uma janela
        viewModel.importStatus.observe(this) { status ->
            status?.let {
                AlertDialog.Builder(this)
                    .setTitle("Controle de Ponto")
                    .setMessage(it)
                    .setPositiveButton("OK") { _, _ -> viewModel.clearImportStatus() }
                    .show()
            }
        }
    }

    private fun updateToolbarSummary() {
        val overtimeMinutes = viewModel.monthlyOvertimeMinutes.value ?: 0L
        val balanceMinutes = viewModel.monthlyBalanceMinutes.value ?: 0L
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
        val goalMinutes = monthlyGoalHours * 60L

        // 1. Saldo líquido (Banco de horas: Extra - Débito)
        val absBalance = Math.abs(balanceMinutes)
        val balanceHours = absBalance / 60
        val balanceMins = absBalance % 60
        val sign = if (balanceMinutes >= 0) "+" else "-"
        val balanceStr = String.format(Locale.getDefault(), "%s%02dh %02dm", sign, balanceHours, balanceMins)
        
        // 2. Extras do Mês (Conforme solicitado: "total de horas extras do mes corrente")
        val eHours = overtimeMinutes / 60
        val eMins = overtimeMinutes % 60
        val extrasStr = String.format(Locale.getDefault(), "%02dh %02dm", eHours, eMins)

        // 3. Percentual alcançado baseado no total de extras vs meta mensal
        val percentage = if (goalMinutes > 0) {
            (overtimeMinutes.toDouble() / goalMinutes * 100).toInt()
        } else 0

        // Exibição atualizada no topo: Saldo | Extras Mês | Meta: %
        binding.tvToolbarMonthlyTotal.text = String.format(Locale.getDefault(), 
            "Saldo: %s | Extras: %s | Meta: %d%%",
            balanceStr, extrasStr, percentage)
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        val current = workDay ?: WorkDay(date)
        
        // Configura cliques nos valores e nos rótulos para facilitar a edição
        val clickListener = View.OnClickListener { view ->
            val timeToEdit = when(view.id) {
                R.id.tvClockIn, R.id.lblClockIn -> current.clockIn
                R.id.tvBreakStart, R.id.lblBreakStart -> current.breakStart
                R.id.tvBreakEnd, R.id.lblBreakEnd -> current.breakEnd
                R.id.tvClockOut, R.id.lblClockOut -> current.clockOut
                else -> null
            }
            
            showTimePicker(timeToEdit) { newTime ->
                val updated = when(view.id) {
                    R.id.tvClockIn, R.id.lblClockIn -> current.copy(clockIn = newTime)
                    R.id.tvBreakStart, R.id.lblBreakStart -> current.copy(breakStart = newTime)
                    R.id.tvBreakEnd, R.id.lblBreakEnd -> current.copy(breakEnd = newTime)
                    R.id.tvClockOut, R.id.lblClockOut -> current.copy(clockOut = newTime)
                    else -> current
                }
                viewModel.updateWorkDay(updated)
            }
        }

        binding.tvClockIn.setOnClickListener(clickListener)
        binding.lblClockIn.setOnClickListener(clickListener)
        binding.tvBreakStart.setOnClickListener(clickListener)
        binding.lblBreakStart.setOnClickListener(clickListener)
        binding.tvBreakEnd.setOnClickListener(clickListener)
        binding.lblBreakEnd.setOnClickListener(clickListener)
        binding.tvClockOut.setOnClickListener(clickListener)
        binding.lblClockOut.setOnClickListener(clickListener)
    }

    private fun showTimePicker(currentTime: LocalTime?, onTimeSelected: (LocalTime) -> Unit) {
        val time = currentTime ?: LocalTime.now()
        TimePickerDialog(this, { _, hour, minute ->
            onTimeSelected(LocalTime.of(hour, minute))
        }, time.hour, time.minute, true).show()
    }

    private fun showDatePicker() {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            viewModel.setDate(LocalDate.of(year, month + 1, dayOfMonth))
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun updateStats(workDay: WorkDay?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val targetMinutes = workHours * 60
        
        binding.tvTarget.text = "Jornada: ${workHours}h | Pausa: ${prefs.getString("break_hours", "2")}h"
        
        val totalWorked = workDay?.calculateTotalMinutes(isToday = workDay.date == LocalDate.now()) ?: 0L
        val hours = totalWorked / 60
        val mins = totalWorked % 60
        
        // Remove "Trabalhado: " prefix to avoid duplication with XML label
        binding.tvTotalWorked.text = String.format(Locale.getDefault(), "%02dh %02dm", hours, mins)
        
        // Calculate Daily Overtime
        val overtimeMinutes = (totalWorked - targetMinutes).coerceAtLeast(0)
        val oHours = overtimeMinutes / 60
        val oMins = overtimeMinutes % 60
        binding.tvDailyOvertime.text = String.format(Locale.getDefault(), "+%02dh %02dm", oHours, oMins)
        
        // Update daily progress bar
        binding.progressWork.max = targetMinutes.toInt()
        binding.progressWork.progress = totalWorked.toInt().coerceAtMost(targetMinutes.toInt())

        // Update remaining time message
        val remaining = (targetMinutes - totalWorked).coerceAtLeast(0)
        val rHours = remaining / 60
        val rMins = remaining % 60
        binding.tvRemaining.text = if (remaining > 0) {
            String.format(Locale.getDefault(), "Faltam %02dh %02dm", rHours, rMins)
        } else {
            "Jornada concluída!"
        }
    }

    private fun updateButtonUI(workDay: WorkDay?) {
        val label = when {
            workDay == null || workDay.clockIn == null -> "ENTRADA"
            workDay.breakStart == null -> "INÍCIO PAUSA"
            workDay.breakEnd == null -> "FIM PAUSA"
            workDay.clockOut == null -> "SAÍDA"
            else -> "CONCLUÍDO"
        }
        binding.btnPunch.text = label
        binding.btnPunch.isEnabled = workDay?.clockOut == null
    }
}
