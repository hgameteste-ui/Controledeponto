/**
 * Nome do Arquivo: WorkViewModel.kt
 * Pacote: com.example.controledeponto
 * Projeto: Controle de Ponto Eletrônico
 *
 * Descrição:
 * ViewModel responsável por gerenciar as regras de negócio, cálculos de jornada de trabalho,
 * banco de horas líquido, projeções mensais e acúmulos trimestrais. Atua como intermediário
 * entre o repositório de dados (Room/SQLite) e a camada de interface do usuário (UI),
 * expondo estados reativos via LiveData e MediatorLiveData.
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 3.0.7    Jun/2026    Walter R. C.    Ajuste na notificação de contagem de registros (backupCountResult)
 *                                      para garantir que a UI receba o total de registros salvos.
 * 3.0.6    Jun/2026    Walter R. C.    Restauração do motor completo para exportar TODO o histórico
 *                                      agora que a causa real do travamento (loop de LiveData) foi sanada.
 * 3.0.5    Jun/2026    Walter R. C.    Inserção de logs detalhados e rastreamento robusto de fluxo
 *                                      para identificar gargalos em tempo de execução no SAF.
 */

package com.example.controledeponto

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class WorkViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: WorkRepository = WorkRepository(
        AppDatabase.getDatabase(application).workDayDao()
    )

    private val _selectedDate = MutableLiveData(LocalDate.now())
    val selectedDate: LiveData<LocalDate> = _selectedDate

    val selectedWorkDay: LiveData<WorkDay?> = _selectedDate.switchMap { date ->
        repository.getWorkDay(date)
    }

    val allWorkDays: LiveData<List<WorkDay>> = repository.allWorkDays

    val holidaysList: LiveData<List<WorkDay>> = repository.getHolidays()

    private val _backupCountResult = MutableLiveData<Int?>()
    val backupCountResult: LiveData<Int?> = _backupCountResult

    // ... (Mantidos os outros LiveData e métodos de cálculo conforme original)

    val monthlyWorkDays: LiveData<List<WorkDay>> = MediatorLiveData<List<WorkDay>>().apply {
        val update = {
            val date = _selectedDate.value
            val list = allWorkDays.value
            if (date != null && list != null) {
                value = list.filter { it.date.month == date.month && it.date.year == date.year }
                    .sortedBy { it.date }
            }
        }
        addSource(allWorkDays) { update() }
        addSource(_selectedDate) { update() }
    }

    val monthlyTotalMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateTotal(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateTotal(allWorkDays.value, date) }
    }

    val monthlyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = true) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDays.value, date, onlySurplus = true) }
    }

    val monthlyBalanceMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = false) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDays.value, date, onlySurplus = false) }
    }

    val rollingQuarterlyBalanceMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateRollingQuarterlyBalance(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateRollingQuarterlyBalance(allWorkDays.value, date) }
    }

    val rollingQuarterlyMonthsOvertime: LiveData<List<Pair<String, Long>>> = MediatorLiveData<List<Pair<String, Long>>>().apply {
        addSource(allWorkDays) { list -> value = calculateRollingQuarterlyMonths(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateRollingQuarterlyMonths(allWorkDays.value, date) }
    }

    val monthlyBusinessDays: LiveData<Int> = _selectedDate.map { date -> calculateBusinessDays(date) }
    val remainingBusinessDays: LiveData<Int> = _selectedDate.map { date -> calculateRemainingBusinessDays(date) }

    val suggestedDailyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        val update = {
            val balance = rollingQuarterlyBalanceMinutes.value ?: 0L
            val remaining = remainingBusinessDays.value ?: 0
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val monthlyGoalHours = prefs.getString("monthly_goal", "30")?.toLongOrNull() ?: 30L
            val quarterlyGoalMinutes = monthlyGoalHours * 3 * 60

            if (remaining > 0) {
                val neededExtraTotal = (quarterlyGoalMinutes - balance).coerceAtLeast(0L)
                value = neededExtraTotal / remaining
            } else value = 0L
        }
        addSource(rollingQuarterlyBalanceMinutes) { update() }
        addSource(remainingBusinessDays) { update() }
    }

    val extrapolatedOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        val update = {
            val overtime = monthlyOvertimeMinutes.value ?: 0L
            val totalBusiness = monthlyBusinessDays.value ?: 0
            val remaining = remainingBusinessDays.value ?: 0
            val elapsed = (totalBusiness - remaining + 1).coerceAtLeast(1)
            value = (overtime.toDouble() / elapsed * totalBusiness).toLong()
        }
        addSource(monthlyOvertimeMinutes) { update() }
        addSource(monthlyBusinessDays) { update() }
        addSource(remainingBusinessDays) { update() }
    }

    val currentMonthOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, LocalDate.now(), onlySurplus = true) }
    }

    val quarterlyOvertimeAccumulated: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateQuarterlyAccumulated(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateQuarterlyAccumulated(allWorkDays.value, date) }
    }

    val quarterlyMonthlyOvertime: LiveData<List<Pair<String, Long>>> = MediatorLiveData<List<Pair<String, Long>>>().apply {
        addSource(allWorkDays) { list -> value = calculateQuarterlyMonthly(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateQuarterlyMonthly(allWorkDays.value, date) }
    }

    private val _importStatus = MutableLiveData<String?>()
    val importStatus: LiveData<String?> = _importStatus

    private val _isProcessing = MutableLiveData<Boolean>(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    // ... (Mantidos os métodos auxiliares: calculateBusinessDays, calculateRemainingBusinessDays, calculateTotal, calculateOvertime, etc.)

    private fun calculateBusinessDays(date: LocalDate): Int {
        var count = 0
        for (i in 1..date.lengthOfMonth()) {
            val d = date.withDayOfMonth(i)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) count++
        }
        return count
    }

    private fun calculateRemainingBusinessDays(selectedDate: LocalDate): Int {
        val now = LocalDate.now()
        if (selectedDate.year < now.year || (selectedDate.year == now.year && selectedDate.monthValue < now.monthValue)) return 0
        val startDay = if (selectedDate.month == now.month && selectedDate.year == now.year) now.dayOfMonth else 1
        var count = 0
        for (i in startDay..selectedDate.lengthOfMonth()) {
            val d = selectedDate.withDayOfMonth(i)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) count++
        }
        return count
    }

    private fun calculateTotal(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        return list.filter { it.date.month == selectedDate.month && it.date.year == selectedDate.year }
            .sumOf { it.calculateTotalMinutes(isToday = it.date == now) }
    }

    private fun calculateOvertime(list: List<WorkDay>?, targetDate: LocalDate?, onlySurplus: Boolean): Long {
        if (list == null || targetDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val dailyGoalMinutes = workHours * 60

        return list.filter {
            it.date.month == targetDate.month && it.date.year == targetDate.year &&
                    (!onlySurplus || it.clockIn != null) && !it.date.isAfter(now)
        }.sumOf { day ->
            val worked = day.calculateTotalMinutes(isToday = day.date == now)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) 0L else dailyGoalMinutes
            val diff = worked - effectiveGoal
            if (onlySurplus) diff.coerceAtLeast(0L) else diff
        }
    }

    private fun calculateRollingQuarterlyBalance(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60

        val startMonth = ((selectedDate.monthValue - 1) / 3) * 3 + 1

        return list.filter { day ->
            day.date.year == selectedDate.year && day.date.monthValue >= startMonth &&
                    day.date.monthValue <= selectedDate.monthValue && !day.date.isAfter(now)
        }.sumOf { day ->
            val worked = day.calculateTotalMinutes(isToday = day.date == now)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) 0L else dailyGoalMinutes
            worked - effectiveGoal
        }
    }

    private fun calculateRollingQuarterlyMonths(list: List<WorkDay>?, selectedDate: LocalDate?): List<Pair<String, Long>> {
        if (list == null || selectedDate == null) return emptyList()
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60

        val targetMonths = listOf(
            selectedDate.minusMonths(2),
            selectedDate.minusMonths(1),
            selectedDate
        )

        return targetMonths.map { target ->
            val monthTotal = list.filter {
                it.date.year == target.year && it.date.monthValue == target.monthValue && !it.date.isAfter(now)
            }.sumOf { day ->
                val worked = day.calculateTotalMinutes(isToday = day.date == now)
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) 0L else dailyGoalMinutes
                worked - effectiveGoal
            }
            val monthName = target.month.getDisplayName(TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
            monthName to monthTotal
        }
    }

    private fun calculateQuarterlyAccumulated(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
        val startMonth = ((selectedDate.monthValue - 1) / 3) * 3 + 1
        return list.filter {
            it.date.year == selectedDate.year && it.date.monthValue >= startMonth &&
                    it.date.monthValue <= selectedDate.monthValue && !it.date.isAfter(now)
        }.sumOf { day ->
            val worked = day.calculateTotalMinutes(isToday = day.date == now)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) 0L else dailyGoalMinutes
            worked - effectiveGoal
        }
    }

    private fun calculateQuarterlyMonthly(list: List<WorkDay>?, selectedDate: LocalDate?): List<Pair<String, Long>> {
        if (list == null || selectedDate == null) return emptyList()
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
        val startMonthValue = ((selectedDate.monthValue - 1) / 3) * 3 + 1
        val result = mutableListOf<Pair<String, Long>>()
        for (m in startMonthValue..selectedDate.monthValue) {
            val monthTotal = list.filter {
                it.date.year == selectedDate.year && it.date.monthValue == m && !it.date.isAfter(now)
            }.sumOf { day ->
                val worked = day.calculateTotalMinutes(isToday = day.date == now)
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) 0L else dailyGoalMinutes
                worked - effectiveGoal
            }
            val monthName = Month.of(m).getDisplayName(TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() }
            result.add(monthName to monthTotal)
        }
        return result
    }

    fun changeAuditMonth(year: Int, monthValue: Int) {
        _selectedDate.value = LocalDate.of(year, monthValue, 1)
    }

    fun setDate(date: LocalDate) { _selectedDate.value = date }

    fun punchClock(customTime: LocalTime? = null) = viewModelScope.launch {
        val date = _selectedDate.value ?: LocalDate.now()
        val timeToRegister = (customTime ?: LocalTime.now()).truncatedTo(ChronoUnit.MINUTES)
        val current = repository.getWorkDaySync(date) ?: WorkDay(date)
        val updated = when {
            current.clockIn == null -> current.copy(clockIn = timeToRegister)
            current.breakStart == null -> current.copy(breakStart = timeToRegister)
            current.breakEnd == null -> current.copy(breakEnd = timeToRegister)
            current.clockOut == null -> current.copy(clockOut = timeToRegister)
            else -> current
        }
        repository.insert(updated)
    }

    fun updateWorkDay(workDay: WorkDay) = viewModelScope.launch { repository.insert(workDay) }

    fun fetchAndSyncHolidays(year: Int) = viewModelScope.launch(Dispatchers.IO) {
        _importStatus.postValue("Sincronizando feriados de $year...")
        var count = 0
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://brasilapi.com.br/api/feriados/v1/$year")
            connection = url.openConnection() as HttpURLConnection
            connection.apply { requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000 }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val jsonObj = jsonArray.getJSONObject(i)
                    val date = LocalDate.parse(jsonObj.getString("date"))
                    val holidayName = jsonObj.getString("name")

                    val existing = repository.getWorkDaySync(date)
                    if (existing == null) {
                        repository.insert(WorkDay(
                            date = date,
                            isHolidayOrOffDay = true,
                            holidayName = holidayName
                        ))
                        count++
                    } else if (!existing.isHolidayOrOffDay) {
                        repository.insert(existing.copy(
                            isHolidayOrOffDay = true,
                            holidayName = holidayName
                        ))
                        count++
                    }
                }
                _importStatus.postValue("Sucesso! $count feriados importados para $year.")
            } else _importStatus.postValue("Falha ao conectar: Erro ${connection.responseCode}")
        } catch (e: Exception) {
            _importStatus.postValue("Falha ao conectar: ${e.localizedMessage}")
        } finally {
            connection?.disconnect()
        }
    }

    fun importCsv(uri: Uri) = viewModelScope.launch {
        _importStatus.postValue("Importando registros...")
        var count = 0
        try {
            withContext(Dispatchers.IO) {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@withContext
                val content = inputStream.bufferedReader().use { it.readText() }
                val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                content.lines().forEachIndexed { index, line ->
                    if (index == 0 || line.isBlank()) return@forEachIndexed
                    val parts = line.split(";").map { it.trim().removeSurrounding("\"") }
                    if (parts.size >= 2) {
                        try {
                            val date = LocalDate.parse(if (parts[0].contains(",")) parts[0].split(",")[1].trim() else parts[0], dateFormatter)
                            val clockIn = try { LocalTime.parse(parts[1], timeFormatter) } catch (e: Exception) { null }
                            if (clockIn != null) {
                                repository.insert(WorkDay(date, clockIn,
                                    try { LocalTime.parse(parts[2], timeFormatter) } catch(e:Exception){null},
                                    try { LocalTime.parse(parts[3], timeFormatter) } catch(e:Exception){null},
                                    try { LocalTime.parse(parts[4], timeFormatter) } catch(e:Exception){null}, false))
                                count++
                            }
                        } catch (e: Exception) { Log.e("WorkViewModel", "Erro linha $index") }
                    }
                }
            }
            _importStatus.postValue("Sucesso! $count registros importados.")
        } catch (e: Exception) { _importStatus.postValue("Erro: ${e.message}") }
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val selectedDate = _selectedDate.value ?: LocalDate.now()
                val list = repository.allWorkDays.value?.filter { it.date.month == selectedDate.month && it.date.year == selectedDate.year }?.sortedBy { it.date } ?: emptyList()
                val builder = StringBuilder("Data;Entrada;Início Pausa;Fim Pausa;Saída;Total Trabalhado\n")
                val tf = DateTimeFormatter.ofPattern("HH:mm"); val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                list.forEach {
                    val worked = it.calculateTotalMinutes(it.date == LocalDate.now())
                    builder.append("${it.date.format(df)};${it.clockIn?.format(tf) ?: ""};${it.breakStart?.format(tf) ?: ""};${it.breakEnd?.format(tf) ?: ""};${it.clockOut?.format(tf) ?: ""};${String.format("%02dh %02dm", worked/60, worked%60)}\n")
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(builder.toString().toByteArray()) }
            }
            _importStatus.postValue("Backup realizado!")
        } catch (e: Exception) { _importStatus.postValue("Erro: ${e.message}") }
    }

    /**
     * Motor de Backup Geral Total 3.0.7
     * Exporta o histórico e notifica a contagem total de registros processados via LiveData.
     */
    fun exportFullHistoryToDrive(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _isProcessing.postValue(true)
        _importStatus.postValue("Iniciando processamento assíncrono total da base de dados...")

        try {
            val allData = repository.getAllWorkDaysSync() ?: emptyList()
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
            val dailyGoalMinutes = workHours * 60

            val tf = DateTimeFormatter.ofPattern("HH:mm")
            val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val ptBr = Locale("pt", "BR")

            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                    writer.write("Data;Dia da Semana;Entrada;Início Intervalo;Fim Intervalo;Saída;" +
                            "Horas Trabalhadas (Minutos);Meta (Minutos);Saldo Líquido do Dia (Minutos);Tipo (Útil/Folga/Feriado)\n")

                    allData.forEach { day ->
                        val isToday = day.date == LocalDate.now()
                        val totalMinutes = day.calculateTotalMinutes(isToday)
                        val dayOfWeekName = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, ptBr)

                        val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                        val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay) 0L else dailyGoalMinutes
                        val balance = totalMinutes - effectiveGoal

                        val tipo = when {
                            day.isHolidayOrOffDay -> "Feriado/Folga"
                            isWeekend -> "Final de Semana"
                            else -> "Dia Útil"
                        }

                        writer.write("${day.date.format(df)};" +
                                "${dayOfWeekName.replaceFirstChar { it.uppercase() }};" +
                                "${day.clockIn?.format(tf) ?: ""};" +
                                "${day.breakStart?.format(tf) ?: ""};" +
                                "${day.breakEnd?.format(tf) ?: ""};" +
                                "${day.clockOut?.format(tf) ?: ""};" +
                                "$totalMinutes;" +
                                "$effectiveGoal;" +
                                "$balance;" +
                                "$tipo\n")
                    }
                    writer.flush()
                }
            }

            // Notifica a quantidade salva para a Activity[cite: 1]
            _backupCountResult.postValue(allData.size)
            _importStatus.postValue("Backup Geral Total gerado com sucesso!")

        } catch (e: Exception) {
            Log.e("PONTO_TRACK", "ERRO NO MOTOR DE BACKUP: ${e.message}", e)
            _backupCountResult.postValue(0)
            _importStatus.postValue("Erro ao processar Backup Geral: ${e.message}")
        } finally {
            _isProcessing.postValue(false)
        }
    }

    fun clearBackupCountResult() { _backupCountResult.value = null }
    fun clearImportStatus() { _importStatus.value = null }
}
