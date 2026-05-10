package com.example.controledeponto

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.example.controledeponto.databinding.ActivityMainBinding
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: WorkViewModel by viewModels()
    
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    // Launcher para selecionar o arquivo CSV para importação
    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            viewModel.importCsv(it)
            Toast.makeText(this, "Importação iniciada...", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher para criar o arquivo CSV para backup
    private val backupCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let {
            viewModel.exportCsv(it)
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            viewModel.selectedWorkDay.value?.let { updateStats(it) }
            refreshHandler.postDelayed(this, 60000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupObservers()
        setupListeners()
        
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
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
            binding.tvDate.text = date.format(dateFormatter)
            if (date == LocalDate.now()) {
                binding.tvDate.setTextColor(resources.getColor(R.color.purple_500, theme))
            } else {
                binding.tvDate.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
            }
            updateToolbarSummary()
        }

        viewModel.selectedWorkDay.observe(this) { workDay ->
            binding.tvClockIn.text = "Entrada: ${workDay?.clockIn?.format(timeFormatter) ?: "--:--"}"
            binding.tvBreakStart.text = "Início Intervalo: ${workDay?.breakStart?.format(timeFormatter) ?: "--:--"}"
            binding.tvBreakEnd.text = "Fim Intervalo: ${workDay?.breakEnd?.format(timeFormatter) ?: "--:--"}"
            binding.tvClockOut.text = "Saída: ${workDay?.clockOut?.format(timeFormatter) ?: "--:--"}"

            updateStats(workDay)
            updateButtonUI(workDay)
            setupManualEdits(workDay)
        }

        viewModel.monthlyOvertimeMinutes.observe(this) { updateToolbarSummary() }

        viewModel.monthlyTotalMinutes.observe(this) { totalMinutes ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
            val goalMinutes = monthlyGoalHours * 60L

            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            val workedStr = String.format(Locale.getDefault(), "%02dh %02dm", hours, mins)
            
            binding.tvMonthlyTotal.text = workedStr
            binding.progressMonthly.max = goalMinutes.toInt()
            binding.progressMonthly.progress = totalMinutes.toInt().coerceAtMost(goalMinutes.toInt())

            val remainingMinutes = goalMinutes - totalMinutes
            if (remainingMinutes > 0) {
                binding.tvMonthlyRemaining.text = String.format(Locale.getDefault(), "Faltam %dh %02dm para a meta de %dh", remainingMinutes / 60, remainingMinutes % 60, monthlyGoalHours)
            } else {
                binding.tvMonthlyRemaining.text = "Meta mensal batida! 🎉"
            }
            
            updateToolbarSummary()
        }

        viewModel.importStatus.observe(this) { status ->
            status?.let {
                AlertDialog.Builder(this)
                    .setTitle("Ponto")
                    .setMessage(it)
                    .setPositiveButton("OK") { _, _ -> viewModel.clearImportStatus() }
                    .show()
            }
        }
    }

    private fun updateToolbarSummary() {
        val overtimeMinutes = viewModel.monthlyOvertimeMinutes.value ?: 0L
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
        val goalMinutes = monthlyGoalHours * 60L

        val overtimeHours = overtimeMinutes / 60
        val overtimeMins = overtimeMinutes % 60
        val overtimeStr = String.format(Locale.getDefault(), "%02dh %02dm", overtimeHours, overtimeMins)
        
        // Percentual alcançado baseado em: Horas Extras / Meta do Mês
        val percentage = if (goalMinutes > 0) {
            (overtimeMinutes.toDouble() / goalMinutes * 100).toInt()
        } else 0

        val totalMinutes = viewModel.monthlyTotalMinutes.value ?: 0L
        val remainingMinutes = (goalMinutes - totalMinutes).coerceAtLeast(0)
        val remainingStr = String.format(Locale.getDefault(), "%02dh %02dm", remainingMinutes / 60, remainingMinutes % 60)

        binding.tvToolbarMonthlyTotal.text = "Extras: $overtimeStr | Falta: $remainingStr | Alcançado: $percentage%"
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        val current = workDay ?: WorkDay(date)
        
        binding.tvClockIn.setOnClickListener { showTimePicker(current.clockIn) { viewModel.updateWorkDay(current.copy(clockIn = it)) } }
        binding.tvBreakStart.setOnClickListener { showTimePicker(current.breakStart) { viewModel.updateWorkDay(current.copy(breakStart = it)) } }
        binding.tvBreakEnd.setOnClickListener { showTimePicker(current.breakEnd) { viewModel.updateWorkDay(current.copy(breakEnd = it)) } }
        binding.tvClockOut.setOnClickListener { showTimePicker(current.clockOut) { viewModel.updateWorkDay(current.copy(clockOut = it)) } }
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
        val breakHours = prefs.getString("break_hours", "2")?.toLong() ?: 2L
        
        binding.tvTarget.text = "Jornada: ${workHours}h | Pausa: ${breakHours}h"
        val isToday = viewModel.selectedDate.value == LocalDate.now()

        if (workDay?.clockIn == null) {
            binding.tvPrediction.text = "Saída Estimada: --:--"
            binding.tvTimeRemaining.text = "Aguardando entrada..."
            binding.tvTotalWorked.text = "Total Trabalhado: 00h 00m"
            binding.progressWork.progress = 0
            return
        }

        val prediction = when {
            workDay.breakStart != null && workDay.breakEnd != null -> {
                val breakDur = Duration.between(workDay.breakStart, workDay.breakEnd)
                workDay.clockIn.plusHours(workHours).plus(breakDur)
            }
            else -> workDay.clockIn.plusHours(workHours + breakHours) 
        }
        binding.tvPrediction.text = "Saída Estimada: ${prediction.format(timeFormatter)}"

        if (isToday && workDay.clockOut == null) {
            val now = LocalTime.now()
            if (now.isBefore(prediction)) {
                val remaining = Duration.between(now, prediction)
                binding.tvTimeRemaining.text = String.format(Locale.getDefault(), "Faltam %02dh %02dm", remaining.toHours(), remaining.toMinutes() % 60)
                binding.tvTimeRemaining.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
            } else {
                val extra = Duration.between(prediction, now)
                binding.tvTimeRemaining.text = String.format(Locale.getDefault(), "Hora extra: %02dh %02dm", extra.toHours(), extra.toMinutes() % 60)
                binding.tvTimeRemaining.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
            }
        } else {
            binding.tvTimeRemaining.text = if (workDay.clockOut != null) "Jornada encerrada" else "Dia finalizado"
            binding.tvTimeRemaining.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }

        val totalMinutes = workDay.calculateTotalMinutes(isToday)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        binding.tvTotalWorked.text = String.format(Locale.getDefault(), "Total Trabalhado: %02dh %02dm", hours, mins)
        
        val goalMinutes = workHours * 60
        binding.progressWork.max = goalMinutes.toInt()
        binding.progressWork.progress = totalMinutes.toInt().coerceAtMost(goalMinutes.toInt())
    }

    private fun updateButtonUI(workDay: WorkDay?) {
        binding.btnPunch.isEnabled = workDay?.clockOut == null
        binding.btnPunch.text = when {
            workDay?.clockIn == null -> "Registrar Entrada"
            workDay.breakStart == null -> "Iniciar Intervalo"
            workDay.breakEnd == null -> "Finalizar Intervalo"
            workDay.clockOut == null -> "Registrar Saída"
            else -> "Jornada Concluída"
        }
    }

    private fun setupListeners() {
        binding.btnPunch.setOnClickListener {
            viewModel.punchClock()
        }

        binding.tvDate.setOnClickListener {
            showDatePicker()
        }

        binding.btnPreviousDay.setOnClickListener {
            val currentDate = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(currentDate.minusDays(1))
        }

        binding.btnNextDay.setOnClickListener {
            val currentDate = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(currentDate.plusDays(1))
        }
    }
}
