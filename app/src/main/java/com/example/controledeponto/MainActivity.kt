/*
 * Nome: MainActivity.kt
 * Versão: 1.8.0
 * Data: 25/05/2024
 * Hora: 21:30
 * Descrição: Atividade principal atualizada para permitir a exclusão da entrada quando não houver registros posteriores e resetar o dia ao excluir o último registro.
 * 
 * Histórico de Modificações:
 * 25/05/2024 13:30 - Implementada digitação manual de horários no diálogo de ajuste.
 * 25/05/2024 17:30 - Implementada edição de intervalos com validações de limites.
 * 25/05/2024 20:30 - Implementada exclusão de registros (entrada, saída, pausas).
 * 25/05/2024 21:30 - Removida restrição que impedia excluir o único registro do dia (entrada) e implementado o reset completo do dia.
 */

package com.example.controledeponto

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.example.controledeponto.databinding.ActivityMainBinding
import com.example.controledeponto.databinding.DialogClockAdjustBinding
import com.example.controledeponto.databinding.DialogIntervalEditBinding
import com.google.android.material.snackbar.Snackbar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: WorkViewModel by viewModels()
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Locale("pt", "BR"))
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private lateinit var intervalAdapter: WorkIntervalAdapter

    private val driveExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            viewModel.exportFullHistoryToDrive(uri)
        }
    }

    private val backupCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(it) }
    }

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importCsv(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        setupRecyclerView()
        handleIntent(intent)
        setupObservers()
        setupListeners()
        checkNotificationPermission()
        updateLastBackupUI()
    }

    private fun setupRecyclerView() {
        intervalAdapter = WorkIntervalAdapter { interval ->
            showEditIntervalDialog(interval)
        }
        binding.rvIntervals.adapter = intervalAdapter
    }

    private fun showEditIntervalDialog(interval: WorkInterval) {
        val dialogBinding = DialogIntervalEditBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        
        var currentStart = interval.startTime
        var currentEnd = interval.endTime ?: LocalTime.now().truncatedTo(ChronoUnit.MINUTES)

        fun updateLabels() {
            dialogBinding.btnEditStart.text = currentStart.format(timeFormatter)
            dialogBinding.btnEditEnd.text = currentEnd.format(timeFormatter)
        }

        dialogBinding.btnEditStart.setOnClickListener {
            showClockAdjustDialog(currentStart) { newTime ->
                if (newTime != null) {
                    currentStart = newTime
                    updateLabels()
                }
            }
        }

        dialogBinding.btnEditEnd.setOnClickListener {
            showClockAdjustDialog(currentEnd) { newTime ->
                if (newTime != null) {
                    currentEnd = newTime
                    updateLabels()
                }
            }
        }

        dialogBinding.btnDeleteInterval.setOnClickListener {
            showDeleteIntervalDialog(interval)
            dialog.dismiss()
        }

        dialogBinding.btnConfirm.setOnClickListener {
            val workDay = viewModel.selectedWorkDay.value
            val otherIntervals = (viewModel.intervals.value ?: emptyList()).filter { it.id != interval.id }
            
            val error = validateIntervalRules(currentStart, currentEnd, workDay, otherIntervals)
            if (error != null) {
                Snackbar.make(dialogBinding.root, error, Snackbar.LENGTH_LONG).show()
            } else {
                viewModel.updateInterval(interval.copy(startTime = currentStart, endTime = currentEnd))
                dialog.dismiss()
            }
        }

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        
        updateLabels()
        dialog.show()
    }

    private fun validateIntervalRules(start: LocalTime, end: LocalTime, workDay: WorkDay?, others: List<WorkInterval>): String? {
        if (start.isAfter(end)) return "O início não pode ser posterior ao fim."
        
        if (workDay?.clockIn != null && start.isBefore(workDay.clockIn)) {
            return "O intervalo não pode iniciar antes da entrada (${workDay.clockIn.format(timeFormatter)})."
        }
        
        if (workDay?.clockOut != null && end.isAfter(workDay.clockOut)) {
            return "O intervalo não pode terminar após a saída (${workDay.clockOut.format(timeFormatter)})."
        }
        
        for (other in others) {
            val oStart = other.startTime
            val oEnd = other.endTime ?: LocalTime.MAX 
            if (start.isBefore(oEnd) && end.isAfter(oStart)) {
                return "Este horário conflita com outro intervalo registrado."
            }
        }
        
        return null
    }

    private fun showDeleteIntervalDialog(interval: WorkInterval) {
        AlertDialog.Builder(this)
            .setTitle("Excluir Intervalo")
            .setMessage("Deseja realmente excluir este intervalo?")
            .setPositiveButton("Excluir") { _, _ ->
                viewModel.deleteInterval(interval)
                Snackbar.make(binding.root, "Intervalo removido", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("SELECTED_DATE")?.let {
            try { viewModel.setDate(LocalDate.parse(it)) } catch (e: Exception) {}
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnPunch.setOnClickListener {
            showClockAdjustDialog(null) { time ->
                if (time != null) viewModel.punchClock(time)
            }
        }
        
        // Listeners para Intervalos com Ajuste Manual (Horário Padrão = Agora)
        binding.btnStartInterval.setOnClickListener {
            showClockAdjustDialog(null) { time ->
                if (time != null) viewModel.startInterval(customTime = time)
            }
        }
        binding.btnEndInterval.setOnClickListener {
            showClockAdjustDialog(null) { time ->
                if (time != null) viewModel.endInterval(customTime = time)
            }
        }

        binding.tvDate.setOnClickListener { showDatePicker() }
        binding.btnPreviousDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.minusDays(1))
        }
        binding.btnNextDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.plusDays(1))
        }
        binding.btnToday.setOnClickListener { viewModel.setDate(LocalDate.now()) }

        binding.cardSaldoTrimestre.setOnClickListener {
            val isVisible = binding.layoutDetails.visibility == View.VISIBLE
            binding.layoutDetails.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            binding.tvDate.text = date.format(dateFormatter).replaceFirstChar { it.uppercase() }
            val color = if (date == LocalDate.now()) R.color.purple_500 else android.R.color.holo_orange_dark
            binding.tvDate.setTextColor(resources.getColor(color, theme))
            updateMonthlySummary()
        }

        viewModel.selectedWorkDay.observe(this) { workDay ->
            binding.tvClockIn.text = workDay?.clockIn?.format(timeFormatter) ?: "--:--"
            binding.tvBreakStart.text = workDay?.breakStart?.format(timeFormatter) ?: "--:--"
            binding.tvBreakEnd.text = workDay?.breakEnd?.format(timeFormatter) ?: "--:--"
            binding.tvClockOut.text = workDay?.clockOut?.format(timeFormatter) ?: "--:--"
            updateStats(workDay); updateButtonUI(workDay); setupManualEdits(workDay)
        }

        // Observar status de intervalo ativo
        viewModel.activeInterval.observe(this) { active ->
            if (active != null) {
                binding.tvIntervalStatus.text = "Status: Em Intervalo (${active.type})"
                binding.tvIntervalStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
                binding.btnStartInterval.isEnabled = false
                binding.btnEndInterval.isEnabled = true
            } else {
                binding.tvIntervalStatus.text = "Status: Trabalhando"
                binding.tvIntervalStatus.setTextColor(resources.getColor(R.color.purple_500, theme))
                binding.btnStartInterval.isEnabled = true
                binding.btnEndInterval.isEnabled = false
            }
            updateStats(viewModel.selectedWorkDay.value)
        }

        viewModel.intervals.observe(this) { list ->
            intervalAdapter.submitList(list)
            updateStats(viewModel.selectedWorkDay.value)
        }

        viewModel.monthlyBalanceMinutes.observe(this) { updateMonthlySummary() }
        viewModel.rollingQuarterlyBalanceMinutes.observe(this) { updateMonthlySummary() }
        
        viewModel.suggestedDailyOvertimeMinutes.observe(this) { minutes ->
            binding.tvSuggestedDaily.text = "Sugestão de extras/dia: ${formatTime(minutes)}"
        }

        viewModel.extrapolatedOvertimeMinutes.observe(this) { minutes ->
            val sign = if (minutes >= 0) "+" else "-"
            binding.tvExtrapolatedOvertime.text = "Projeção final do mês: $sign${formatTime(minutes)}"
        }

        viewModel.importStatus.observe(this) { status ->
            status?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearImportStatus()
            }
        }

        viewModel.backupCountResult.observe(this) { count ->
            if (count != null) {
                updateLastBackupUI()
                viewModel.clearBackupCountResult()
            }
        }
    }

    private fun showClockAdjustDialog(initialTime: LocalTime?, onDelete: (() -> Unit)? = null, onConfirm: (LocalTime?) -> Unit) {
        val dialogBinding = DialogClockAdjustBinding.inflate(LayoutInflater.from(this))
        var adjustedTime: LocalTime? = initialTime ?: LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        var isHourSelected = true // Foco inicial no campo de horas
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()

        if (onDelete != null && initialTime != null) {
            dialogBinding.btnDelete.visibility = View.VISIBLE
            dialogBinding.btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Excluir Registro")
                    .setMessage("Deseja realmente excluir este horário?")
                    .setPositiveButton("Excluir") { _, _ ->
                        onDelete()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        fun updateTimeDisplay(fromTextWatcher: Boolean = false) {
            if (adjustedTime != null) {
                val hourStr = String.format(Locale.getDefault(), "%02d", adjustedTime!!.hour)
                val minuteStr = String.format(Locale.getDefault(), "%02d", adjustedTime!!.minute)
                
                if (!fromTextWatcher) {
                    if (dialogBinding.tvAdjustedHour.text.toString() != hourStr) {
                        dialogBinding.tvAdjustedHour.setText(hourStr)
                    }
                    if (dialogBinding.tvAdjustedMinute.text.toString() != minuteStr) {
                        dialogBinding.tvAdjustedMinute.setText(minuteStr)
                    }
                }
                
                dialogBinding.tvAdjustedHour.alpha = if (isHourSelected) 1.0f else 0.5f
                dialogBinding.tvAdjustedMinute.alpha = if (!isHourSelected) 1.0f else 0.5f
            }
        }

        val textWatcher = object : TextWatcher {
            private var isInternalChange = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isInternalChange) return
                
                val hText = dialogBinding.tvAdjustedHour.text.toString()
                val mText = dialogBinding.tvAdjustedMinute.text.toString()
                
                val h = hText.toIntOrNull() ?: 0
                val m = mText.toIntOrNull() ?: 0
                
                val validH = h.coerceIn(0, 23)
                val validM = m.coerceIn(0, 59)
                
                if (h != validH || m != validM) {
                    isInternalChange = true
                    if (h != validH) dialogBinding.tvAdjustedHour.setText(String.format(Locale.getDefault(), "%02d", validH))
                    if (m != validM) dialogBinding.tvAdjustedMinute.setText(String.format(Locale.getDefault(), "%02d", validM))
                    isInternalChange = false
                }
                
                adjustedTime = LocalTime.of(validH, validM)
                updateTimeDisplay(true)
            }
        }

        dialogBinding.tvAdjustedHour.addTextChangedListener(textWatcher)
        dialogBinding.tvAdjustedMinute.addTextChangedListener(textWatcher)

        dialogBinding.tvAdjustedHour.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isHourSelected = true
                updateTimeDisplay(true)
                dialogBinding.tvAdjustedHour.selectAll()
            }
        }

        dialogBinding.tvAdjustedMinute.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isHourSelected = false
                updateTimeDisplay(true)
                dialogBinding.tvAdjustedMinute.selectAll()
            }
        }

        updateTimeDisplay()

        dialogBinding.btnPlus.setOnClickListener {
            adjustedTime = if (isHourSelected) adjustedTime?.plusHours(1) else adjustedTime?.plusMinutes(1)
            updateTimeDisplay()
        }
        dialogBinding.btnMinus.setOnClickListener {
            adjustedTime = if (isHourSelected) adjustedTime?.minusHours(1) else adjustedTime?.minusMinutes(1)
            updateTimeDisplay()
        }
        
        dialogBinding.btnClearTime.setOnClickListener {
            adjustedTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
            updateTimeDisplay()
        }

        dialogBinding.btnConfirm.setOnClickListener { onConfirm(adjustedTime); dialog.dismiss() }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateMonthlySummary() {
        val balanceMinutes = viewModel.monthlyBalanceMinutes.value ?: 0L
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val goalMinutes = (prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160) * 60L

        binding.tvMesSaldo.text = (if (balanceMinutes >= 0) "+" else "-") + formatTime(balanceMinutes)
        binding.tvMesMeta.text = formatHours(goalMinutes)
        binding.tvMesFalta.text = formatTime(goalMinutes - balanceMinutes)
        binding.tvMesPercent.text = "${if (goalMinutes > 0) (balanceMinutes.toDouble() / goalMinutes * 100).toInt() else 0}%"
    }

    private fun updateStats(workDay: WorkDay?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val targetMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
        val selectedDate = viewModel.selectedDate.value ?: LocalDate.now()
        val isToday = selectedDate == LocalDate.now()
        val currentIntervals = viewModel.intervals.value ?: emptyList()
        
        val totalWorked = workDay?.calculateTotalMinutes(currentIntervals, isToday) ?: 0L
        binding.tvTotalWorked.text = formatTime(totalWorked)
        
        val effectiveGoal = if (selectedDate.dayOfWeek.value > 5 || workDay?.isHolidayOrOffDay == true) 0L else targetMinutes
        binding.tvDailyOvertime.text = (if (totalWorked >= effectiveGoal) "+" else "-") + formatTime(totalWorked - effectiveGoal)
        
        binding.progressWork.max = targetMinutes.toInt()
        binding.progressWork.progress = totalWorked.toInt().coerceAtMost(targetMinutes.toInt())

        val nextEvent = workDay?.getNextPrediction(targetMinutes, currentIntervals)
        binding.tvPrediction.text = nextEvent?.let { "${it.first} Estimada: ${it.second.format(timeFormatter)}" } ?: "Jornada Concluída"
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
        
        // Ocultar controles de intervalo se o expediente estiver encerrado ou não iniciado
        val hasStarted = workDay?.clockIn != null
        binding.cardIntervalControls.visibility = if (hasStarted) View.VISIBLE else View.GONE
        
        // Desabilitar botões de intervalo se a jornada já foi encerrada
        val isFinished = workDay?.clockOut != null
        if (isFinished) {
            binding.btnStartInterval.isEnabled = false
            binding.btnEndInterval.isEnabled = false
            binding.tvIntervalStatus.text = "Jornada Encerrada"
        }
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        val current = workDay ?: WorkDay(date)
        val listener = View.OnClickListener { view ->
            val time = when(view.id) {
                R.id.tvClockIn, R.id.lblClockIn -> current.clockIn
                R.id.tvBreakStart, R.id.lblBreakStart -> current.breakStart
                R.id.tvBreakEnd, R.id.lblBreakEnd -> current.breakEnd
                R.id.tvClockOut, R.id.lblClockOut -> current.clockOut
                else -> null
            }
            
            val deleteAction: (() -> Unit)? = if (time != null) {
                {
                    val canDelete = canDeleteRecord(view.id, current)
                    if (canDelete == null) {
                        val totalIntervals = viewModel.intervals.value?.size ?: 0
                        val fields = listOf(current.clockIn, current.breakStart, current.breakEnd, current.clockOut)
                        val activeFields = fields.count { it != null }

                        if (activeFields + totalIntervals == 1 && !current.isHolidayOrOffDay) {
                             viewModel.deleteWorkDay(current)
                        } else {
                            val updated = when(view.id) {
                                R.id.tvClockIn, R.id.lblClockIn -> current.copy(clockIn = null)
                                R.id.tvBreakStart, R.id.lblBreakStart -> current.copy(breakStart = null)
                                R.id.tvBreakEnd, R.id.lblBreakEnd -> current.copy(breakEnd = null)
                                R.id.tvClockOut, R.id.lblClockOut -> current.copy(clockOut = null)
                                else -> current
                            }
                            viewModel.updateWorkDay(updated)
                        }
                    } else {
                        Snackbar.make(binding.root, canDelete, Snackbar.LENGTH_LONG).show()
                    }
                }
            } else null

            showClockAdjustDialog(time, deleteAction) { newTime ->
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
        binding.tvClockIn.setOnClickListener(listener); binding.lblClockIn.setOnClickListener(listener)
        binding.tvBreakStart.setOnClickListener(listener); binding.lblBreakStart.setOnClickListener(listener)
        binding.tvBreakEnd.setOnClickListener(listener); binding.lblBreakEnd.setOnClickListener(listener)
        binding.tvClockOut.setOnClickListener(listener); binding.lblClockOut.setOnClickListener(listener)
    }

    private fun canDeleteRecord(viewId: Int, workDay: WorkDay): String? {
        val totalIntervals = viewModel.intervals.value?.size ?: 0

        return when (viewId) {
            R.id.tvClockIn, R.id.lblClockIn -> {
                if (workDay.clockOut != null || workDay.breakStart != null || totalIntervals > 0) 
                    "Exclua os registros posteriores antes de remover a entrada."
                else null
            }
            R.id.tvBreakStart, R.id.lblBreakStart -> {
                if (workDay.breakEnd != null) "Exclua o fim do intervalo antes de remover o início."
                else null
            }
            R.id.tvClockOut, R.id.lblClockOut -> {
                null
            }
            else -> null
        }
    }

    private fun showDatePicker() {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        DatePickerDialog(this, { _, y, m, d -> viewModel.setDate(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun updateLastBackupUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.tvLastBackupStatus.text = "Nuvem: ${prefs.getString("last_backup_status", "N/A")} em ${prefs.getString("last_backup_date", "Nunca")}"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main_menu, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatTime(minutes: Long): String {
        val m = Math.abs(minutes)
        return String.format("%02dh %02dm", m / 60, m % 60)
    }
    
    private fun formatHours(minutes: Long): String = "${minutes / 60}h"
}
