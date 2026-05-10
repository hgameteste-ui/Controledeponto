package com.example.controledeponto

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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

    val monthlyTotalMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateTotal(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateTotal(allWorkDays.value, date) }
    }

    val monthlyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDays.value, date) }
    }

    private fun calculateTotal(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        return list.filter { it.date.month == selectedDate.month && it.date.year == selectedDate.year }
            .sumOf { it.calculateTotalMinutes(isToday = it.date == now) }
    }

    private fun calculateOvertime(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val dailyGoalMinutes = workHours * 60

        return list.filter { it.date.month == selectedDate.month && it.date.year == selectedDate.year }
            .sumOf { 
                val worked = it.calculateTotalMinutes(isToday = it.date == now)
                (worked - dailyGoalMinutes).coerceAtLeast(0)
            }
    }

    private val _importStatus = MutableLiveData<String?>()
    val importStatus: LiveData<String?> = _importStatus

    fun setDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun punchClock() = viewModelScope.launch {
        val date = _selectedDate.value ?: LocalDate.now()
        val now = LocalTime.now()
        val current = repository.getWorkDaySync(date) ?: WorkDay(date)

        val updated = when {
            current.clockIn == null -> current.copy(clockIn = now)
            current.breakStart == null -> current.copy(breakStart = now)
            current.breakEnd == null -> current.copy(breakEnd = now)
            current.clockOut == null -> current.copy(clockOut = now)
            else -> current
        }

        repository.insert(updated)
    }

    fun updateWorkDay(workDay: WorkDay) = viewModelScope.launch {
        repository.insert(workDay)
    }

    fun importCsv(uri: Uri) = viewModelScope.launch {
        _importStatus.postValue("Importando registros...")
        var count = 0
        val errorLines = mutableListOf<String>()

        try {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Arquivo não encontrado")
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val content = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { String(bytes, Charsets.ISO_8859_1) }
                
                val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

                content.lines().forEachIndexed { index, rawLine ->
                    val line = rawLine.trim().replace("\uFEFF", "")
                    if (index == 0 || line.isBlank()) return@forEachIndexed 
                    
                    // Separador ponto e vírgula conforme seu exemplo
                    val parts = line.split(";").map { it.trim().removeSurrounding("\"").trim() }
                    
                    if (parts.size >= 2) {
                        try {
                            // Limpa a data: "qua., 01/04/26" -> extrai "01/04/26"
                            val rawDate = parts[0]
                            val dateStr = if (rawDate.contains(",")) rawDate.split(",")[1].trim() else rawDate
                            
                            val date = LocalDate.parse(dateStr, dateFormatter)
                            
                            fun parseTime(s: String?): LocalTime? {
                                if (s.isNullOrBlank()) return null
                                return try { LocalTime.parse(s, timeFormatter) } catch (e: Exception) { null }
                            }

                            val clockIn = parseTime(parts.getOrNull(1))
                            // Só importa se houver pelo menos entrada
                            if (clockIn != null) {
                                val workDay = WorkDay(
                                    date = date,
                                    clockIn = clockIn,
                                    breakStart = parseTime(parts.getOrNull(2)),
                                    breakEnd = parseTime(parts.getOrNull(3)),
                                    clockOut = parseTime(parts.getOrNull(4))
                                )
                                repository.insert(workDay)
                                count++
                            }
                        } catch (e: Exception) {
                            if (errorLines.size < 3) errorLines.add("Linha ${index + 1}: ${e.localizedMessage}")
                        }
                    }
                }
            }
            
            _importStatus.postValue(if (count > 0) "Sucesso! $count registros de Abril importados." 
                                   else "Erro: Nenhum dado válido encontrado. Verifique o formato do arquivo.")
            
        } catch (e: Exception) {
            _importStatus.postValue("Falha ao abrir arquivo: ${e.localizedMessage}")
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }
}
