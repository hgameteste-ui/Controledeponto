/*
 * Nome: WorkViewModel.kt
 * Versão: 2.7.0
 * Data: 12/02/2025
 * Hora: 17:15
 * Descrição: ViewModel responsável pela lógica de negócio.
 * Atualizada para suportar a sinalização de faltas (isAbsence).
 * 
 * Histórico de Modificações:
 * 25/05/2024 21:00 - Adicionado método deleteWorkDay para permitir resetar o estado do dia completamente.
 * 12/02/2025 14:00 - Corrigido erro "Limit must be non-negative" ao remover o parâmetro limit = -1 do split.
 * 12/02/2025 17:15 - Adicionado método toggleAbsence para alternar o status de falta do dia.
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
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
        AppDatabase.getDatabase(application).workDayDao(),
        AppDatabase.getDatabase(application).intervalDao()
    )

    private val _selectedDate = MutableLiveData(LocalDate.now())
    val selectedDate: LiveData<LocalDate> = _selectedDate

    val selectedWorkDay: LiveData<WorkDay?> = _selectedDate.switchMap { date ->
        repository.getWorkDay(date)
    }

    val intervals: LiveData<List<WorkInterval>> = _selectedDate.switchMap { date ->
        repository.getIntervalsByDate(date).asLiveData()
    }

    val activeInterval: LiveData<WorkInterval?> = intervals.map { list ->
        list.find { it.endTime == null }
    }

    val totalBreakMinutes: LiveData<Long> = intervals.map { list ->
        list.sumOf { interval ->
            val end = interval.endTime ?: LocalTime.now()
            ChronoUnit.MINUTES.between(interval.startTime, end)
        }
    }

    val allWorkDays: LiveData<List<WorkDay>> = repository.allWorkDays
    val allWorkDaysWithIntervals: LiveData<List<WorkDayWithIntervals>> = repository.allWorkDaysWithIntervals
    val holidaysList: LiveData<List<WorkDay>> = repository.getHolidays()

    private val _backupCountResult = MutableLiveData<Int?>()
    val backupCountResult: LiveData<Int?> = _backupCountResult

    val monthlyWorkDaysWithIntervals: LiveData<List<WorkDayWithIntervals>> = MediatorLiveData<List<WorkDayWithIntervals>>().apply {
        val update = {
            val date = _selectedDate.value
            val list = allWorkDaysWithIntervals.value
            if (date != null && list != null) {
                value = list.filter { it.workDay.date.month == date.month && it.workDay.date.year == date.year }
                    .sortedBy { it.workDay.date }
            }
        }
        addSource(allWorkDaysWithIntervals) { update() }
        addSource(_selectedDate) { update() }
    }

    val monthlyTotalMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDaysWithIntervals) { list -> value = calculateTotal(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateTotal(allWorkDaysWithIntervals.value, date) }
    }

    val monthlyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDaysWithIntervals) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = true) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDaysWithIntervals.value, date, onlySurplus = true) }
    }

    val monthlyBalanceMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDaysWithIntervals) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = false) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDaysWithIntervals.value, date, onlySurplus = false) }
    }

    val rollingQuarterlyBalanceMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDaysWithIntervals) { list -> value = calculateRollingQuarterlyBalance(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateRollingQuarterlyBalance(allWorkDaysWithIntervals.value, date) }
    }

    val quarterlyMonthlyOvertime: LiveData<List<Pair<String, Long>>> = MediatorLiveData<List<Pair<String, Long>>>().apply {
        val update = {
            val list = allWorkDaysWithIntervals.value
            val date = _selectedDate.value
            if (list != null && date != null) {
                value = calculateQuarterlyMonthlyBreakdown(list, date)
            }
        }
        addSource(allWorkDaysWithIntervals) { update() }
        addSource(_selectedDate) { update() }
    }

    val monthlyBusinessDays: LiveData<Int> = _selectedDate.map { date -> calculateBusinessDays(date) }
    val remainingBusinessDays: LiveData<Int> = _selectedDate.map { date -> calculateRemainingBusinessDays(date) }

    val suggestedDailyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        val update = {
            val balance = rollingQuarterlyBalanceMinutes.value ?: 0L
            val remaining = remainingBusinessDays.value ?: 0
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val monthlyGoalHours = prefs.getString("monthly_goal", "160")?.toLongOrNull() ?: 160L
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

    private val _importStatus = MutableLiveData<String?>()
    val importStatus: LiveData<String?> = _importStatus

    private val _isProcessing = MutableLiveData<Boolean>(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _csvPreview = MutableLiveData<List<WorkDay>?>()
    val csvPreview: LiveData<List<WorkDay>?> = _csvPreview

    private val _importReport = MutableLiveData<String?>()
    val importReport: LiveData<String?> = _importReport

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

    private fun calculateTotal(list: List<WorkDayWithIntervals>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        return list.filter { it.workDay.date.month == selectedDate.month && it.workDay.date.year == selectedDate.year }
            .sumOf { it.calculateTotalMinutes(isToday = it.workDay.date == now) }
    }

    private fun calculateOvertime(list: List<WorkDayWithIntervals>?, targetDate: LocalDate?, onlySurplus: Boolean): Long {
        if (list == null || targetDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val dailyGoalMinutes = workHours * 60

        return list.filter {
            it.workDay.date.month == targetDate.month && it.workDay.date.year == targetDate.year &&
                    (!onlySurplus || it.workDay.clockIn != null || it.workDay.isAbsence) && !it.workDay.date.isAfter(now)
        }.sumOf { dayWithIntervals ->
            val worked = dayWithIntervals.calculateTotalMinutes(isToday = dayWithIntervals.workDay.date == now)
            val day = dayWithIntervals.workDay
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null && !day.isAbsence)) 0L else dailyGoalMinutes
            val diff = worked - effectiveGoal
            if (onlySurplus) diff.coerceAtLeast(0L) else diff
        }
    }

    private fun calculateRollingQuarterlyBalance(list: List<WorkDayWithIntervals>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60

        val startMonth = ((selectedDate.monthValue - 1) / 3) * 3 + 1

        return list.filter { dayWithIntervals ->
            val day = dayWithIntervals.workDay
            day.date.year == selectedDate.year && day.date.monthValue >= startMonth &&
                    day.date.monthValue <= selectedDate.monthValue && !day.date.isAfter(now)
        }.sumOf { dayWithIntervals ->
            val worked = dayWithIntervals.calculateTotalMinutes(isToday = dayWithIntervals.workDay.date == now)
            val day = dayWithIntervals.workDay
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null && !day.isAbsence)) 0L else dailyGoalMinutes
            worked - effectiveGoal
        }
    }

    private fun calculateQuarterlyMonthlyBreakdown(list: List<WorkDayWithIntervals>, selectedDate: LocalDate): List<Pair<String, Long>> {
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
        val startMonth = ((selectedDate.monthValue - 1) / 3) * 3 + 1
        
        val result = mutableListOf<Pair<String, Long>>()
        for (m in startMonth..selectedDate.monthValue) {
            val month = Month.of(m)
            val balance = list.filter { dayWithIntervals ->
                val day = dayWithIntervals.workDay
                day.date.year == selectedDate.year && day.date.month == month && !day.date.isAfter(now)
            }.sumOf { dayWithIntervals ->
                val worked = dayWithIntervals.calculateTotalMinutes(isToday = dayWithIntervals.workDay.date == now)
                val day = dayWithIntervals.workDay
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null && !day.isAbsence)) 0L else dailyGoalMinutes
                worked - effectiveGoal
            }
            val monthName = month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            result.add(monthName to balance)
        }
        return result
    }

    fun setDate(date: LocalDate) { _selectedDate.value = date }

    fun changeAuditMonth(year: Int, month: Int) {
        _selectedDate.value = LocalDate.of(year, month, 1)
    }

    fun toggleAbsence() = viewModelScope.launch {
        val date = _selectedDate.value ?: LocalDate.now()
        val current = repository.getWorkDaySync(date) ?: WorkDay(date)
        
        // Só permite sinalizar falta se não houver registros de ponto
        if (current.clockIn != null || current.clockOut != null) {
            _importStatus.postValue("Não é possível marcar falta em um dia com registros de ponto.")
            return@launch
        }
        
        val updated = current.copy(isAbsence = !current.isAbsence)
        repository.insert(updated)
    }

    fun punchClock(customTime: LocalTime? = null) = viewModelScope.launch {
        val date = _selectedDate.value ?: LocalDate.now()
        val timeToRegister = (customTime ?: LocalTime.now()).truncatedTo(ChronoUnit.MINUTES)
        val current = repository.getWorkDaySync(date) ?: WorkDay(date)
        
        if (current.isAbsence) {
            _importStatus.postValue("Remova a sinalização de falta antes de registrar ponto.")
            return@launch
        }

        val updated = when {
            current.clockIn == null -> current.copy(clockIn = timeToRegister)
            current.breakStart == null -> current.copy(breakStart = timeToRegister)
            current.breakEnd == null -> current.copy(breakEnd = timeToRegister)
            current.clockOut == null -> current.copy(clockOut = timeToRegister)
            else -> current
        }
        repository.insert(updated)
    }

    fun startInterval(type: String = "BREAK", customTime: LocalTime? = null) = viewModelScope.launch {
        val date = _selectedDate.value ?: LocalDate.now()
        val timeToRegister = (customTime ?: LocalTime.now()).truncatedTo(ChronoUnit.MINUTES)
        
        val current = repository.getWorkDaySync(date)
        if (current?.isAbsence == true) {
            _importStatus.postValue("Remova a sinalização de falta antes de iniciar intervalo.")
            return@launch
        }

        val intervalType = try {
            IntervalType.valueOf(type.uppercase(Locale.getDefault()))
        } catch (e: Exception) {
            IntervalType.BREAK
        }

        if (repository.getWorkDaySync(date) == null) {
            repository.insert(WorkDay(date = date))
        }
        
        repository.insertInterval(WorkInterval(
            workDayId = date,
            startTime = timeToRegister,
            type = intervalType
        ))
    }

    fun endInterval(customTime: LocalTime? = null) = viewModelScope.launch {
        val active = activeInterval.value ?: return@launch
        val timeToRegister = (customTime ?: LocalTime.now()).truncatedTo(ChronoUnit.MINUTES)
        repository.updateInterval(active.copy(endTime = timeToRegister))
    }

    fun updateInterval(interval: WorkInterval) = viewModelScope.launch {
        repository.updateInterval(interval)
    }

    fun deleteInterval(interval: WorkInterval) = viewModelScope.launch {
        repository.deleteInterval(interval)
    }

    fun deleteWorkDay(workDay: WorkDay) = viewModelScope.launch {
        repository.deleteWorkDay(workDay)
    }

    fun getIntervalsForDay(): LiveData<List<WorkInterval>> = intervals
    fun getTotalBreakTime(): LiveData<Long> = totalBreakMinutes

    fun updateWorkDay(workDay: WorkDay) = viewModelScope.launch { 
        if (workDay.isAbsence && (workDay.clockIn != null || workDay.clockOut != null)) {
             _importStatus.postValue("Não é possível ter registros de ponto em um dia de falta.")
             return@launch
        }
        repository.insert(workDay) 
    }

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
                        repository.insert(WorkDay(date = date, isHolidayOrOffDay = true, holidayName = holidayName))
                        count++
                    } else if (!existing.isHolidayOrOffDay) {
                        repository.insert(existing.copy(isHolidayOrOffDay = true, holidayName = holidayName))
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

    private fun flexibleParseDate(dateStr: String): LocalDate? {
        val formats = listOf("dd/MM/yyyy", "d/M/yyyy", "dd/MM/yy", "d/M/yy", "yyyy-MM-dd", "yyyy/MM/dd")
        for (format in formats) {
            try {
                return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(format))
            } catch (e: Exception) {}
        }
        return try { LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE) } catch(e: Exception) { null }
    }

    private fun flexibleParseTime(timeStr: String): LocalTime? {
        val clean = timeStr.trim()
        if (clean.isEmpty()) return null
        val formats = listOf("HH:mm", "H:mm", "HH:mm:ss", "H:mm:ss")
        for (format in formats) {
            try {
                return LocalTime.parse(clean, DateTimeFormatter.ofPattern(format))
            } catch (e: Exception) {}
        }
        return null
    }

    fun importCsv(uri: Uri) = viewModelScope.launch {
        _isProcessing.postValue(true)
        val previewList = mutableListOf<WorkDay>()
        val detailReport = StringBuilder()
        var totalLinesInFile = 0
        var processedLines = 0
        var validRecords = 0
        var errorCount = 0
        var ignoredCount = 0

        try {
            withContext(Dispatchers.IO) {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@withContext
                val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                
                reader.lineSequence().forEachIndexed { index, rawLine ->
                    totalLinesInFile++
                    val line = rawLine.replace("\uFEFF", "").trim()
                    
                    if (index == 0) {
                        Log.d("WorkViewModel", "Pulando cabeçalho (Linha 0): '$line'")
                        return@forEachIndexed
                    }
                    
                    processedLines++
                    if (line.isEmpty()) {
                        ignoredCount++
                        Log.d("WorkViewModel", "Linha $index vazia ignorada.")
                        return@forEachIndexed
                    }
                    
                    val parts = line.split(";").map { it.trim().removeSurrounding("\"") }
                    Log.d("WorkViewModel", "Lendo Linha $index (Colunas: ${parts.size}): $parts")

                    if (parts.isNotEmpty()) {
                        val date = flexibleParseDate(parts[0])
                        if (date == null) {
                            errorCount++
                            val msg = "Linha ${index + 1}: Data '${parts[0]}' inválida. Use dd/MM/yyyy."
                            detailReport.append(msg).append("\n")
                            Log.e("WorkViewModel", msg)
                            return@forEachIndexed
                        }

                        val clockIn = if (parts.size > 1) flexibleParseTime(parts[1]) else null
                        val breakStart = if (parts.size > 2) flexibleParseTime(parts[2]) else null
                        val breakEnd = if (parts.size > 3) flexibleParseTime(parts[3]) else null
                        val clockOut = if (parts.size > 4) flexibleParseTime(parts[4]) else null
                        
                        if (clockIn != null || clockOut != null || breakStart != null || breakEnd != null) {
                            previewList.add(WorkDay(date, clockIn, breakStart, breakEnd, clockOut))
                            validRecords++
                            Log.d("WorkViewModel", "Linha $index validada: $date")
                        } else {
                            ignoredCount++
                            val msg = "Linha ${index + 1}: Data $date sem horários. Ignorada."
                            detailReport.append(msg).append("\n")
                            Log.w("WorkViewModel", msg)
                        }
                    } else {
                        ignoredCount++
                        Log.w("WorkViewModel", "Linha $index sem colunas.")
                    }
                }
            }
            
            val summary = "Relatório de Leitura:\n" +
                          "- Linhas no arquivo: $totalLinesInFile\n" +
                          "- Linhas de dados processadas: $processedLines\n" +
                          "- Registros válidos (com horários): $validRecords\n" +
                          "- Linhas ignoradas (vazias/sem dados): $ignoredCount\n" +
                          "- Linhas com erro de formato: $errorCount\n\n"
            
            _importReport.postValue(summary + (if (detailReport.isNotEmpty()) "Problemas encontrados:\n$detailReport" else "Nenhum erro de parsing detectado."))

            if (previewList.isEmpty()) {
                _importStatus.postValue("Nenhum registro para importar. Verifique se o arquivo segue o formato.")
            } else {
                _csvPreview.postValue(previewList)
            }
        } catch (e: Exception) {
            _importStatus.postValue("Falha crítica ao ler arquivo: ${e.message}")
            Log.e("WorkViewModel", "Erro em importCsv", e)
        } finally {
            _isProcessing.postValue(false)
        }
    }

    fun confirmImport(data: List<WorkDay>) = viewModelScope.launch {
        _isProcessing.postValue(true)
        var importedCount = 0
        var duplicateCount = 0
        try {
            withContext(Dispatchers.IO) {
                data.forEach { workDay ->
                    val existing = repository.getWorkDaySync(workDay.date)
                    if (existing == null) {
                        repository.insert(workDay)
                        importedCount++
                    } else {
                        duplicateCount++
                    }
                }
            }
            _importStatus.postValue("Importação Concluída: $importedCount novos, $duplicateCount já existiam.")
            _csvPreview.postValue(null)
        } catch (e: Exception) {
            _importStatus.postValue("Erro ao salvar registros: ${e.message}")
        } finally {
            _isProcessing.postValue(false)
        }
    }

    fun clearCsvPreview() { _csvPreview.value = null }
    fun clearImportReport() { _importReport.value = null }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val selectedDate = _selectedDate.value ?: LocalDate.now()
                val list = repository.getAllWorkDaysWithIntervalsSync().filter { it.workDay.date.month == selectedDate.month && it.workDay.date.year == selectedDate.year }.sortedBy { it.workDay.date }
                val builder = StringBuilder("Data;Entrada;Início Pausa;Fim Pausa;Saída;Total Trabalhado\n")
                val tf = DateTimeFormatter.ofPattern("HH:mm"); val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                list.forEach {
                    val worked = it.calculateTotalMinutes(isToday = it.workDay.date == LocalDate.now())
                    val day = it.workDay
                    builder.append("${day.date.format(df)};${day.clockIn?.format(tf) ?: ""};${day.breakStart?.format(tf) ?: ""};${day.breakEnd?.format(tf) ?: ""};${day.clockOut?.format(tf) ?: ""};${String.format("%02dh %02dm", worked/60, worked%60)}\n")
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(builder.toString().toByteArray()) }
            }
            _importStatus.postValue("Backup realizado!")
        } catch (e: Exception) { _importStatus.postValue("Erro: ${e.message}") }
    }

    fun exportFullHistoryToDrive(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _isProcessing.postValue(true)
        try {
            val allData = repository.getAllWorkDaysSync()
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val dailyGoalMinutes = (prefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
            val tf = DateTimeFormatter.ofPattern("HH:mm"); val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val ptBr = Locale("pt", "BR")

            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                    writer.write("Data;Dia da Semana;Entrada;Início Intervalo;Fim Intervalo;Saída;Horas Trabalhadas;Meta;Saldo;Tipo\n")
                    allData.forEach { day ->
                        val totalMinutes = day.calculateTotalMinutes(isToday = day.date == LocalDate.now())
                        val effectiveGoal = if (day.date.dayOfWeek.value > 5 || day.isHolidayOrOffDay) 0L else dailyGoalMinutes
                        writer.write("${day.date.format(df)};${day.date.dayOfWeek.getDisplayName(TextStyle.FULL, ptBr)};${day.clockIn?.format(tf) ?: ""};${day.breakStart?.format(tf) ?: ""};${day.breakEnd?.format(tf) ?: ""};${day.clockOut?.format(tf) ?: ""};$totalMinutes;$effectiveGoal;${totalMinutes-effectiveGoal};${if(day.isHolidayOrOffDay) "Feriado" else "Util"}\n")
                    }
                }
            }
            _backupCountResult.postValue(allData.size)
        } catch (e: Exception) { _backupCountResult.postValue(0) } finally { _isProcessing.postValue(false) }
    }

    fun clearBackupCountResult() { _backupCountResult.value = null }
    fun clearImportStatus() { _importStatus.value = null }
}
