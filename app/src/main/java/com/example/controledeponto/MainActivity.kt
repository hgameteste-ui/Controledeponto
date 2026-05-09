package com.example.controledeponto

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.controledeponto.databinding.ActivityMainBinding
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: WorkViewModel by viewModels()
    private lateinit var historyAdapter: HistoryAdapter
    
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupObservers()
        setupListeners()
        
        // Exibe a versão atualizada automaticamente do build.gradle
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter { workDay ->
            viewModel.setDate(workDay.date)
        }
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            binding.tvDate.text = date.format(dateFormatter)
            // Destacar se for hoje ou um dia diferente
            if (date == LocalDate.now()) {
                binding.tvDate.setTextColor(resources.getColor(R.color.purple_500, theme))
            } else {
                binding.tvDate.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
            }
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

        viewModel.allWorkDays.observe(this) { list ->
            // Filtra o dia que está sendo editado para não aparecer no histórico abaixo
            val selectedDate = viewModel.selectedDate.value ?: LocalDate.now()
            historyAdapter.submitList(list.filter { it.date != selectedDate })
        }

        // Observa o total acumulado do mês e atualiza no topo a direita
        viewModel.monthlyTotalMinutes.observe(this) { totalMinutes ->
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            binding.tvMonthlyTotal.text = String.format("Mês: %02dh %02dm", hours, mins)
        }
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

        if (workDay?.clockIn == null) {
            binding.tvPrediction.text = "Previsão de Saída: --:--"
            binding.tvTotalWorked.text = "Total Trabalhado: 00h 00m"
            binding.progressWork.progress = 0
            return
        }

        // 1. Previsão Dinâmica
        val prediction = when {
            workDay.breakStart != null && workDay.breakEnd != null -> {
                val breakDur = Duration.between(workDay.breakStart, workDay.breakEnd)
                workDay.clockIn.plusHours(workHours).plus(breakDur)
            }
            else -> workDay.clockIn.plusHours(workHours + breakHours) 
        }
        binding.tvPrediction.text = "Previsão de Saída: ${prediction.format(timeFormatter)}"

        // 2. Tempo Trabalhado (Usa a lógica centralizada no WorkDay)
        val isToday = viewModel.selectedDate.value == LocalDate.now()
        val totalMinutes = workDay.calculateTotalMinutes(isToday)

        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        binding.tvTotalWorked.text = String.format("Total Trabalhado: %02dh %02dm", hours, mins)
        
        // 3. Barra de Progresso
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
    }
}
