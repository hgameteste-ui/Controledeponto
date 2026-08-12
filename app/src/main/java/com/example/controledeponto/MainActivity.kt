/*
 * Nome: MainActivity.kt
 * Versão: 4.4.0
 * Data: 13/02/2025
 * Hora: 15:15
 * Descrição: Atividade principal atualizada para o modelo único de intervalos.
 * Adicionada exclusão individual de horários do intervalo regular.
 */

package com.example.controledeponto

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
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

        binding.btnToggleAbsence.setOnClickListener {
            viewModel.toggleAbsence()
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
            binding.tvClockOut.text = workDay?.clockOut?.format(timeFormatter) ?: "--:--"
            
            if (workDay?.isHolidayOrOffDay == true) {
                binding.tvHolidayBadge.visibility = View.VISIBLE
                binding.tvHolidayBadge.text = workDay.holidayName ?: "FERIADO"
            } else {
                binding.tvHolidayBadge.visibility = View.GONE
            }

            if (workDay?.isAbsence == true) {
                binding.tvAbsenceBadge.visibility = View.VISIBLE
                binding.btnToggleAbsence.text = "Remover Sinalização de Falta"
                binding.btnPunch.isEnabled = false
            } else {
                binding.tvAbsenceBadge.visibility = View.GONE
                binding.btnToggleAbsence.text = "Sinalizar Falta do Dia"
                binding.btnPunch.isEnabled = workDay?.clockOut == null
            }

            updateStats(workDay); updateButtonUI(workDay); setupManualEdits(workDay)
        }

        viewModel.activeInterval.observe(this) { active ->
            if (active != null) {
                binding.tvIntervalStatus.text = "Status: Em Intervalo (${active.type})"
                binding.tvIntervalStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
                binding.btnStartInterval.isEnabled = false
                binding.btnEndInterval.isEnabled = true
            } else {
                binding.tvIntervalStatus.text = "Status: Trabalhando"
                binding.tvIntervalStatus.setTextColor(resources.getColor(R.color.purple_500, theme))
                binding.btnStartInterval.isEnabled = viewModel.selectedWorkDay.value?.isAbsence != true && viewModel.selectedWorkDay.value?.clockIn != null && viewModel.selectedWorkDay.value?.clockOut == null
                binding.btnEndInterval.isEnabled = false
            }
            updateStats(viewModel.selectedWorkDay.value)
        }

        viewModel.intervals.observe(this) { list ->
            intervalAdapter.submitList(list)
            
            // Popula os campos do intervalo "legado" (topo) com a primeira pausa da lista
            val firstInterval = list.sortedBy { it.startTime }.firstOrNull()
            binding.tvBreakStart.text = firstInterval?.startTime?.format(timeFormatter) ?: "--:--"
            binding.tvBreakEnd.text = firstInterval?.endTime?.format(timeFormatter) ?: "--:--"
            
            if (firstInterval != null) {
                val end = firstInterval.endTime ?: if (viewModel.selectedDate.value == LocalDate.now()) LocalTime.now() else firstInterval.startTime
                val dur = ChronoUnit.MINUTES.between(firstInterval.startTime, end).coerceAtLeast(0)
                binding.tvLegacyIntervalDuration.text = "intervalo ${formatDurationTimeline(dur)}"
                binding.tvLegacyIntervalDuration.visibility = View.VISIBLE
            } else {
                binding.tvLegacyIntervalDuration.visibility = View.GONE
            }

            updateStats(viewModel.selectedWorkDay.value)
            updateButtonUI(viewModel.selectedWorkDay.value)
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

        viewModel.csvPreview.observe(this) { preview ->
            if (preview != null && preview.isNotEmpty()) {
                showCsvPreviewDialog(preview)
            }
        }

        viewModel.importReport.observe(this) { report ->
            if (report != null) {
                showErrorReport(report)
            }
        }

        viewModel.backupCountResult.observe(this) { count ->
            if (count != null) {
                updateLastBackupUI()
                viewModel.clearBackupCountResult()
            }
        }
    }

    private fun showErrorReport(report: String) {
        AlertDialog.Builder(this)
            .setTitle("Relatório de Importação")
            .setMessage(report)
            .setPositiveButton("OK") { _, _ -> viewModel.clearImportReport() }
            .show()
    }

    private fun showCsvPreviewDialog(data: List<WorkDay>) {
        val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val tf = DateTimeFormatter.ofPattern("HH:mm")
        val summary = StringBuilder("Resumo da Prévia:\n\n")
        
        data.take(10).forEach { 
            summary.append("${it.date.format(df)}: ${it.clockIn?.format(tf) ?: "--"} > ${it.clockOut?.format(tf) ?: "--"}\n")
        }
        
        if (data.size > 10) {
            summary.append("... e mais ${data.size - 10} registros.")
        } else {
            summary.append("\nTotal: ${data.size} registros prontos para importar.")
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Importação")
            .setMessage(summary.toString())
            .setPositiveButton("Importar Tudo") { _, _ ->
                viewModel.confirmImport(data)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                viewModel.clearCsvPreview()
            }
            .setCancelable(false)
            .show()
    }

    private fun showClockAdjustDialog(initialTime: LocalTime?, onDelete: (() -> Unit)? = null, onConfirm: (LocalTime?) -> Unit) {
        val dialogBinding = DialogClockAdjustBinding.inflate(LayoutInflater.from(this))
        var adjustedTime: LocalTime? = initialTime ?: LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        var isHourSelected = true 
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
        val currentIntervals = viewModel.intervals.value ?: emptyList()
        
        val totalWorked = workDay?.calculateTotalMinutes(currentIntervals, selectedDate == LocalDate.now()) ?: 0L
        binding.tvTotalWorked.text = formatTime(totalWorked)
        
        val totalInterval = workDay?.calculateBreakMinutes(currentIntervals, selectedDate == LocalDate.now()) ?: 0L
        binding.tvTotalBreakTime.text = "Total em Intervalo: ${formatTime(totalInterval)}"

        val isHoliday = workDay?.isHolidayOrOffDay == true
        val effectiveGoal = if (selectedDate.dayOfWeek.value > 5 || isHoliday) 0L else targetMinutes
        
        // Peso 2 para feriados no saldo diário
        val weightedWorked = if (isHoliday) totalWorked * 2 else totalWorked
        val balance = weightedWorked - effectiveGoal
        
        binding.tvDailyOvertime.text = (if (balance >= 0) "+" else "-") + formatTime(balance)
        binding.tvDailyOvertime.setTextColor(resources.getColor(if (balance >= 0) android.R.color.holo_green_dark else android.R.color.holo_red_dark, theme))
        
        val nextEvent = workDay?.getNextPrediction(targetMinutes, currentIntervals)
        binding.tvPrediction.text = when {
            workDay?.isAbsence == true -> "Dia de Falta Sinalizada"
            isHoliday && totalWorked == 0L -> "Feriado: Meta Zero"
            else -> nextEvent?.let { "${it.first} Estimada: ${it.second.format(timeFormatter)}" } ?: "Jornada Concluída"
        }
        
        binding.tvRemaining.text = when {
            workDay?.isAbsence == true -> "Meta do dia será descontada do saldo"
            isHoliday && totalWorked == 0L -> "Aproveite o descanso!"
            totalWorked < effectiveGoal -> "Faltam ${formatTime(effectiveGoal - totalWorked)}"
            else -> "Meta atingida!"
        }
    }

    private fun formatDurationTimeline(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) String.format(Locale("pt", "BR"), "%02dh%02d", h, m) else String.format(Locale("pt", "BR"), "%02dm", m)
    }

    private fun updateButtonUI(workDay: WorkDay?) {
        val currentIntervals = viewModel.intervals.value ?: emptyList()
        val active = currentIntervals.find { it.endTime == null }
        
        val label = when {
            workDay == null || workDay.clockIn == null -> "ENTRADA"
            active != null -> "FIM PAUSA"
            workDay.clockOut == null -> {
                if (currentIntervals.isEmpty()) "INÍCIO PAUSA" else "SAÍDA"
            }
            else -> "CONCLUÍDO"
        }
        binding.btnPunch.text = label
        binding.btnPunch.isEnabled = workDay?.clockOut == null && workDay?.isAbsence != true
        
        val hasStarted = workDay?.clockIn != null && workDay?.isAbsence != true
        binding.cardIntervalControls.visibility = if (hasStarted) View.VISIBLE else View.GONE
        
        val isFinished = workDay?.clockOut != null || workDay?.isAbsence == true
        if (isFinished) {
            binding.btnStartInterval.isEnabled = false
            binding.btnEndInterval.isEnabled = false
            binding.tvIntervalStatus.text = if (workDay?.isAbsence == true) "Dia com Falta" else "Jornada Encerrada"
        }

        binding.btnToggleAbsence.isEnabled = (workDay == null || workDay.clockIn == null)
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        val current = workDay ?: WorkDay(date)
        val currentIntervals = viewModel.intervals.value ?: emptyList()
        val firstInterval = currentIntervals.sortedBy { it.startTime }.firstOrNull()

        val listener = View.OnClickListener { view ->
            if (current.isAbsence) {
                Snackbar.make(binding.root, "Remova a sinalização de falta para editar horários.", Snackbar.LENGTH_LONG).show()
                return@OnClickListener
            }

            when(view.id) {
                R.id.tvClockIn, R.id.lblClockIn -> {
                    showClockAdjustDialog(current.clockIn, if (current.clockIn != null) ({ 
                        if (current.clockOut != null || currentIntervals.isNotEmpty()) {
                             Snackbar.make(binding.root, "Exclua os registros posteriores antes de remover a entrada.", Snackbar.LENGTH_LONG).show()
                        } else {
                            viewModel.deleteWorkDay(current)
                        }
                    }) else null) { newTime ->
                        viewModel.updateWorkDay(current.copy(clockIn = newTime))
                    }
                }
                R.id.tvBreakStart, R.id.lblBreakStart -> {
                    if (firstInterval != null) {
                        showClockAdjustDialog(firstInterval.startTime, {
                            viewModel.deleteInterval(firstInterval)
                        }) { newTime ->
                            if (newTime != null) viewModel.updateInterval(firstInterval.copy(startTime = newTime))
                        }
                    } else {
                        showClockAdjustDialog(null) { newTime ->
                            if (newTime != null) viewModel.startInterval("LUNCH", newTime)
                        }
                    }
                }
                R.id.tvBreakEnd, R.id.lblBreakEnd -> {
                    if (firstInterval != null) {
                        showClockAdjustDialog(firstInterval.endTime, {
                            viewModel.updateInterval(firstInterval.copy(endTime = null))
                        }) { newTime ->
                            if (newTime != null) viewModel.updateInterval(firstInterval.copy(endTime = newTime))
                        }
                    } else {
                        showClockAdjustDialog(null) { newTime ->
                            if (newTime != null) viewModel.endInterval(newTime)
                        }
                    }
                }
                R.id.tvClockOut, R.id.lblClockOut -> {
                    showClockAdjustDialog(current.clockOut, if (current.clockOut != null) ({ 
                        viewModel.updateWorkDay(current.copy(clockOut = null))
                    }) else null) { newTime ->
                        viewModel.updateWorkDay(current.copy(clockOut = newTime))
                    }
                }
            }
        }
        binding.tvClockIn.setOnClickListener(listener); binding.lblClockIn.setOnClickListener(listener)
        binding.tvBreakStart.setOnClickListener(listener); binding.lblBreakStart.setOnClickListener(listener)
        binding.tvBreakEnd.setOnClickListener(listener); binding.lblBreakEnd.setOnClickListener(listener)
        binding.tvClockOut.setOnClickListener(listener); binding.lblClockOut.setOnClickListener(listener)
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
            R.id.action_audit_month -> {
                startActivity(Intent(this, AuditMonthlyActivity::class.java))
                true
            }
            R.id.action_quarterly_statement -> {
                startActivity(Intent(this, QuarterlyStatementActivity::class.java))
                true
            }
            R.id.action_manage_holidays -> {
                startActivity(Intent(this, HolidaysConfigActivity::class.java))
                true
            }
            R.id.action_sync_holidays -> {
                viewModel.fetchAndSyncHolidays(LocalDate.now().year)
                true
            }
            R.id.action_trigger_backup_flow -> {
                driveExportLauncher.launch("ControlePonto_FullBackup_${LocalDate.now()}.csv")
                true
            }
            R.id.action_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_import -> { importCsvLauncher.launch("text/*"); true }
            R.id.action_backup -> { backupCsvLauncher.launch("ControlePonto_Backup_${LocalDate.now().monthValue}_${LocalDate.now().year}.csv"); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatTime(minutes: Long): String {
        val m = Math.abs(minutes)
        return String.format(Locale("pt", "BR"), "%02dh %02dm", m / 60, m % 60)
    }
    
    private fun formatHours(minutes: Long): String = "${minutes / 60}h"
}
