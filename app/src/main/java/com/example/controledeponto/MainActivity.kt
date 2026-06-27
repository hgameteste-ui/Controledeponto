/**
 * Nome do Arquivo: MainActivity.kt
 * Pacote: com.example.controledeponto
 * Projeto: Controle de Ponto Eletrônico
 * 
 * Descrição:
 * Atividade principal da aplicação que gerencia o fluxo de controle de ponto diário, 
 * mensal e trimestral. Estabelece os vínculos de observação (Observers) com o [WorkViewModel], 
 * manipula componentes visuais via View Binding e gerencia ações da barra de menu superior 
 * (histórico, configurações, rotinas de backup e sincronização manual de feriados públicos).
 *
 * Funcionalidades da Interface:
 * 1. Alteração Dinâmica de Datas: Navegação entre dias anteriores, posteriores e atalho para o dia atual.
 * 2. Edição Manual Dinâmica: Permite clicar sobre os rótulos ou horários de ponto para abrir um dialog de ajuste.
 * 3. Sincronização de Saldos: Mostra valores de créditos ou débitos na barra de ferramentas e no sumário.
 * 4. Alarme Integrado: Comunica-se com o [AlarmManager] para agendar predições de intervalo e saída com tolerância.
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 1.9.9    Jun/2026    Walter R. C.    Refatoração do painel de progresso para exibir o saldo acumulado 
 *                                      dos últimos 3 meses (rolling quarterly balance).
 * 1.9.8    Jun/2026    Walter R. C.    Remoção do bloco trimestral do painel principal para ganho de área útil.
 * 1.9.0    Jun/2026    Walter R. C.    Acesso à tela HolidaysConfigActivity via Toolbar.
 * 1.8.9    Jun/2026    Walter R. C.    Adição de ação manual na Toolbar para sincronizar feriados do ano
 *                                      via BrasilAPI com base na data em exibição na UI.
 * 1.8.6    Jun/2026    Walter R. C.    Implementação de clique longo na data para alternar Feriado/Folga.
 * 1.8.5    Jun/2026    Walter R. C.    Integração visual com o novo campo [isHolidayOrOffDay],
 *                                      sanando a cobrança de horas em feriados e folgas na interface.
 */

package com.example.controledeponto

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
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

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupObservers()
        setupListeners()
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnPunch.setOnClickListener { viewModel.punchClock() }
        binding.tvDate.setOnClickListener { showDatePicker() }
        binding.tvDate.setOnLongClickListener {
            showHolidayDialog(viewModel.selectedWorkDay.value)
            true
        }
        binding.btnPreviousDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.minusDays(1))
        }
        binding.btnNextDay.setOnClickListener {
            val current = viewModel.selectedDate.value ?: LocalDate.now()
            viewModel.setDate(current.plusDays(1))
        }
        binding.btnToday.setOnClickListener { viewModel.setDate(LocalDate.now()) }
    }

    private fun showHolidayDialog(workDay: WorkDay?) {
        val selectedDate = viewModel.selectedDate.value ?: LocalDate.now()
        val currentWorkDay = workDay ?: WorkDay(date = selectedDate)
        val isHoliday = currentWorkDay.isHolidayOrOffDay
        val message = if (isHoliday) "Deseja remover a marcação de Feriado/Folga?" else "Deseja marcar este dia como Feriado/Folga?"

        AlertDialog.Builder(this)
            .setTitle("Controle de Ponto")
            .setMessage(message)
            .setPositiveButton("Confirmar") { _, _ ->
                viewModel.updateWorkDay(currentWorkDay.copy(isHolidayOrOffDay = !isHoliday))
            }
            .setNegativeButton("Cancelar", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_quarterly_statement -> {
                startActivity(Intent(this, QuarterlyStatementActivity::class.java))
                true
            }
            R.id.action_manage_holidays -> {
                startActivity(Intent(this, HolidaysConfigActivity::class.java))
                true
            }
            R.id.action_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
            R.id.action_sync_holidays -> {
                val year = (viewModel.selectedDate.value ?: LocalDate.now()).year
                viewModel.fetchAndSyncHolidays(year)
                true
            }
            R.id.action_backup -> {
                val date = viewModel.selectedDate.value ?: LocalDate.now()
                backupCsvLauncher.launch("backup_ponto_${date.monthValue}_${date.year}.csv")
                true
            }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_import -> { importCsvLauncher.launch("*/*"); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupObservers() {
        viewModel.selectedDate.observe(this) { date ->
            binding.tvDate.text = date.format(dateFormatter).replaceFirstChar { it.uppercase() }
            val color = if (date == LocalDate.now()) R.color.purple_500 else android.R.color.holo_orange_dark
            binding.tvDate.setTextColor(resources.getColor(color, theme))
            updateToolbarSummary()
        }

        viewModel.selectedWorkDay.observe(this) { workDay ->
            binding.tvClockIn.text = workDay?.clockIn?.format(timeFormatter) ?: "--:--"
            binding.tvBreakStart.text = workDay?.breakStart?.format(timeFormatter) ?: "--:--"
            binding.tvBreakEnd.text = workDay?.breakEnd?.format(timeFormatter) ?: "--:--"
            binding.tvClockOut.text = workDay?.clockOut?.format(timeFormatter) ?: "--:--"
            updateStats(workDay); updateButtonUI(workDay); setupManualEdits(workDay)
        }

        viewModel.monthlyBalanceMinutes.observe(this) { updateToolbarSummary() }
        
        // Atualizado para observar o saldo trimestral acumulado (rolling quarterly balance)
        viewModel.rollingQuarterlyBalanceMinutes.observe(this) { balanceMinutes ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
            val quarterlyGoalMinutes = monthlyGoalHours * 3 * 60L

            val absBalance = Math.abs(balanceMinutes)
            val hours = absBalance / 60
            val mins = absBalance % 60
            val sign = if (balanceMinutes >= 0) "+" else "-"
            
            binding.tvMonthlyTotal.text = String.format(Locale.getDefault(), "%s%02dh %02dm", sign, hours, mins)
            binding.progressMonthly.max = quarterlyGoalMinutes.toInt()
            binding.progressMonthly.progress = balanceMinutes.coerceAtLeast(0L).toInt().coerceAtMost(quarterlyGoalMinutes.toInt())

            val remainingMinutes = (quarterlyGoalMinutes - balanceMinutes).coerceAtLeast(0)
            binding.tvMonthlyRemaining.text = if (remainingMinutes > 0) {
                String.format(Locale.getDefault(), "Faltam %dh %02dm para a meta trimestral de %dh", 
                    remainingMinutes / 60, remainingMinutes % 60, monthlyGoalHours * 3)
            } else {
                "Meta trimestral batida! 🎉"
            }
            updateToolbarSummary()
        }

        viewModel.suggestedDailyOvertimeMinutes.observe(this) { minutes ->
            val hours = minutes / 60
            val mins = minutes % 60
            binding.tvSuggestedDaily.text = String.format(Locale.getDefault(), "Sugestão de extras/dia: %02dh %02dm", hours, mins)
        }

        viewModel.extrapolatedOvertimeMinutes.observe(this) { minutes ->
            val absMinutes = Math.abs(minutes)
            val hours = absMinutes / 60
            val mins = absMinutes % 60
            val sign = if (minutes >= 0) "+" else "-"
            binding.tvExtrapolatedOvertime.text = String.format(Locale.getDefault(), "Projeção final do mês: %s%02dh %02dm", sign, hours, mins)
        }

        viewModel.monthlyBusinessDays.observe(this) { total ->
            val remaining = viewModel.remainingBusinessDays.value ?: 0
            binding.tvMonthlyBusinessDays.text = "Dias úteis: $total | Restantes: $remaining"
        }

        viewModel.remainingBusinessDays.observe(this) { remaining ->
            val total = viewModel.monthlyBusinessDays.value ?: 0
            binding.tvMonthlyBusinessDays.text = "Dias úteis: $total | Restantes: $remaining"
        }

        viewModel.importStatus.observe(this) { status ->
            status?.let {
                AlertDialog.Builder(this).setTitle("Controle de Ponto").setMessage(it)
                    .setPositiveButton("OK") { _, _ -> viewModel.clearImportStatus() }.show()
            }
        }
    }

    private fun updateToolbarSummary() {
        val overtimeMinutes = viewModel.monthlyOvertimeMinutes.value ?: 0L
        val balanceMinutes = viewModel.monthlyBalanceMinutes.value ?: 0L
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toIntOrNull() ?: 160
        val goalMinutes = monthlyGoalHours * 60L

        val absBalance = Math.abs(balanceMinutes)
        val balanceHours = absBalance / 60
        val balanceMins = absBalance % 60
        val sign = if (balanceMinutes >= 0) "+" else "-"
        val balanceStr = String.format(Locale.getDefault(), "%s%02dh %02dm", sign, balanceHours, balanceMins)

        val eHours = overtimeMinutes / 60
        val eMins = overtimeMinutes % 60
        val extrasStr = String.format(Locale.getDefault(), "%02dh %02dm", eHours, eMins)

        val percentage = if (goalMinutes > 0) (overtimeMinutes.toDouble() / goalMinutes * 100).toInt() else 0
        binding.tvToolbarMonthlyTotal.text = String.format(Locale.getDefault(), "Saldo: %s | Extras: %s | Meta: %d%%", balanceStr, extrasStr, percentage)
    }

    private fun setupManualEdits(workDay: WorkDay?) {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        val current = workDay ?: WorkDay(date)

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

        binding.tvClockIn.setOnClickListener(clickListener); binding.lblClockIn.setOnClickListener(clickListener)
        binding.tvBreakStart.setOnClickListener(clickListener); binding.lblBreakStart.setOnClickListener(clickListener)
        binding.tvBreakEnd.setOnClickListener(clickListener); binding.lblBreakEnd.setOnClickListener(clickListener)
        binding.tvClockOut.setOnClickListener(clickListener); binding.lblClockOut.setOnClickListener(clickListener)
    }

    private fun showTimePicker(currentTime: LocalTime?, onTimeSelected: (LocalTime) -> Unit) {
        val time = currentTime ?: LocalTime.now()
        TimePickerDialog(this, { _, hour, minute -> onTimeSelected(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show()
    }

    private fun showDatePicker() {
        val date = viewModel.selectedDate.value ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, dayOfMonth -> viewModel.setDate(LocalDate.of(year, month + 1, dayOfMonth)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun updateStats(workDay: WorkDay?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val breakHours = prefs.getString("break_hours", "2")?.toLong() ?: 2L
        val targetMinutes = workHours * 60
        val breakMinutes = breakHours * 60

        binding.tvTarget.text = "Jornada: ${workHours}h | Pausa: ${breakHours}h"

        val selectedDate = viewModel.selectedDate.value ?: LocalDate.now()
        val isToday = selectedDate == LocalDate.now()
        val isWeekend = selectedDate.dayOfWeek == java.time.DayOfWeek.SATURDAY || selectedDate.dayOfWeek == java.time.DayOfWeek.SUNDAY
        val isHolidayOrOff = workDay?.isHolidayOrOffDay ?: false

        val totalWorked = workDay?.calculateTotalMinutes(isToday = isToday) ?: 0L
        binding.tvTotalWorked.text = String.format(Locale.getDefault(), "%02dh %02dm", totalWorked / 60, totalWorked % 60)

        val effectiveGoal = if (isWeekend || isHolidayOrOff || (isToday && workDay?.clockIn == null)) 0L else targetMinutes
        val balanceMinutes = totalWorked - effectiveGoal
        val absBalance = Math.abs(balanceMinutes)

        val sign = when {
            balanceMinutes > 0 -> "+"
            balanceMinutes < 0 -> "-"
            else -> ""
        }
        binding.tvDailyOvertime.text = String.format(Locale.getDefault(), "%s%02dh %02dm", sign, absBalance / 60, absBalance % 60)

        binding.progressWork.max = targetMinutes.toInt()
        binding.progressWork.progress = totalWorked.toInt().coerceAtMost(targetMinutes.toInt())

        val remaining = (effectiveGoal - totalWorked).coerceAtLeast(0L)
        binding.tvRemaining.text = if (remaining > 0) {
            String.format(Locale.getDefault(), "Faltam %02dh %02dm", remaining / 60, remaining % 60)
        } else {
            if ((isWeekend || isHolidayOrOff) && totalWorked == 0L) "Folga / Feriado" else "Jornada concluída!"
        }

        if (workDay != null) {
            if (workDay.breakStart != null) cancelNotification(2)
            if (workDay.breakEnd != null) cancelNotification(3)
            if (workDay.clockOut != null) cancelNotification(4)

            val nextEvent = workDay.getNextPrediction(targetMinutes, breakMinutes)
            if (nextEvent != null) {
                val (eventName, eventTime) = nextEvent
                binding.tvPrediction.text = "$eventName Estimada: ${eventTime.format(timeFormatter)}"
                if (isToday) scheduleNotification(eventName, eventTime)
            } else {
                binding.tvPrediction.text = if (workDay.clockOut != null) "Expediente encerrado" else "Saída Estimada: --:--"
            }
        } else {
            binding.tvPrediction.text = when {
                isToday -> "Aguardando primeira batida"
                isWeekend || isHolidayOrOff -> "Feriado ou final de semana"
                else -> "Sem registros para este dia"
            }
        }
    }

    private fun scheduleNotification(type: String, time: LocalTime) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, NotificationReceiver::class.java).apply { putExtra("type", type) }
        val requestCode = when(type) {
            "Início do Intervalo" -> 2
            "Fim do Intervalo" -> 3
            "Saída" -> 4
            else -> 0
        }
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            add(Calendar.MINUTE, 15)
        }
        if (calendar.timeInMillis > System.currentTimeMillis()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                }
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }
    }

    private fun cancelNotification(requestCode: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, Intent(this, NotificationReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pendingIntent != null) alarmManager.cancel(pendingIntent)
    }

    private fun updateButtonUI(workDay: WorkDay?) {
        val label = when {
            workDay?.isHolidayOrOffDay == true && workDay.clockIn == null -> "FERIADO / FOLGA"
            workDay == null || workDay.clockIn == null -> "ENTRADA"
            workDay.breakStart == null -> "INÍCIO PAUSA"
            workDay.breakEnd == null -> "FIM PAUSA"
            workDay.clockOut == null -> "SAÍDA"
            else -> "CONCLUÍDO"
        }
        binding.btnPunch.text = label
        binding.btnPunch.isEnabled = workDay?.clockOut == null && workDay?.isHolidayOrOffDay != true
    }
}
