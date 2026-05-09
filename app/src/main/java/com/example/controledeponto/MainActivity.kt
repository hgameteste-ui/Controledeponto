package com.example.controledeponto

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
    private val historyAdapter = HistoryAdapter()
    
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
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun setupObservers() {
        viewModel.todayWorkDay.observe(this) { workDay ->
            val today = LocalDate.now()
            binding.tvDate.text = workDay?.date?.format(dateFormatter) ?: today.format(dateFormatter)
            
            binding.tvClockIn.text = "Entrada: ${workDay?.clockIn?.format(timeFormatter) ?: "--:--"}"
            binding.tvBreakStart.text = "Início Intervalo: ${workDay?.breakStart?.format(timeFormatter) ?: "--:--"}"
            binding.tvBreakEnd.text = "Fim Intervalo: ${workDay?.breakEnd?.format(timeFormatter) ?: "--:--"}"
            binding.tvClockOut.text = "Saída: ${workDay?.clockOut?.format(timeFormatter) ?: "--:--"}"

            updateStats(workDay)
            updateButtonUI(workDay)
            setupManualEdits(workDay)
        }

        viewModel.allWorkDays.observe(this) { list ->
            val today = LocalDate.now()
            historyAdapter.submitList(list.filter { it.date != today })
        }
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val current = workDay ?: WorkDay(LocalDate.now())
        
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

        val clockIn = workDay.clockIn
        val breakStart = workDay.breakStart
        val breakEnd = workDay.breakEnd
        val clockOut = workDay.clockOut

        // 1. Previsão Dinâmica
        val prediction = when {
            breakStart != null && breakEnd != null -> {
                val breakDur = Duration.between(breakStart, breakEnd)
                clockIn.plusHours(workHours).plus(breakDur)
            }
            else -> clockIn.plusHours(workHours + breakHours) 
        }
        binding.tvPrediction.text = "Previsão de Saída: ${prediction.format(timeFormatter)}"

        // 2. Tempo Trabalhado
        var totalMinutes = 0L
        val now = LocalTime.now()

        val end1 = breakStart ?: if (clockOut == null) now else clockIn
        totalMinutes += Duration.between(clockIn, end1).toMinutes()

        if (breakEnd != null) {
            val end2 = clockOut ?: now
            if (end2.isAfter(breakEnd)) {
                totalMinutes += Duration.between(breakEnd, end2).toMinutes()
            }
        }

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
    }
}
