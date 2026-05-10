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

        // Filtra apenas dias do mês selecionado que já tiveram algum registro
        return list.filter { 
            it.date.month == selectedDate.month && 
            it.date.year == selectedDate.year &&
            it.clockIn != null 
        }.sumOf { 
            val worked = it.calculateTotalMinutes(isToday = it.date == now)
            worked - dailyGoalMinutes
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
                    
                    val parts = line.split(";").map { it.trim().removeSurrounding("\"").trim() }
                    
                    if (parts.size >= 2) {
                        try {
                            val rawDate = parts[0]
                            val dateStr = if (rawDate.contains(",")) rawDate.split(",")[1].trim() else rawDate
                            val date = LocalDate.parse(dateStr, dateFormatter)
                            
                            fun parseTime(s: String?): LocalTime? {
                                if (s.isNullOrBlank()) return null
                                return try { LocalTime.parse(s, timeFormatter) } catch (e: Exception) { null }
                            }

                            val clockIn = parseTime(parts.getOrNull(1))
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
                            Log.e("WorkViewModel", "Erro na linha $index: ${e.message}")
                        }
                    }
                }
            }
            _importStatus.postValue(if (count > 0) "Sucesso! $count registros importados." else "Nenhum dado válido encontrado.")
        } catch (e: Exception) {
            _importStatus.postValue("Falha ao abrir arquivo: ${e.localizedMessage}")
        }
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val selectedDate = _selectedDate.value ?: LocalDate.now()
                val list = repository.allWorkDays.value?.filter { 
                    it.date.month == selectedDate.month && it.date.year == selectedDate.year 
                }?.sortedBy { it.date } ?: emptyList()

                val builder = StringBuilder()
                builder.append("Data;Entrada;Início Pausa;Fim Pausa;Saída;Total Trabalhado\n")
                
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

                list.forEach { 
                    val workedMinutes = it.calculateTotalMinutes(isToday = it.date == LocalDate.now())
                    val workedStr = String.format(Locale.getDefault(), "%02dh %02dm", workedMinutes / 60, workedMinutes % 60)
                    
                    builder.append("${it.date.format(dateFormatter)};")
                    builder.append("${it.clockIn?.format(timeFormatter) ?: ""};")
                    builder.append("${it.breakStart?.format(timeFormatter) ?: ""};")
                    builder.append("${it.breakEnd?.format(timeFormatter) ?: ""};")
                    builder.append("${it.clockOut?.format(timeFormatter) ?: ""};")
                    builder.append("$workedStr\n")
                }

                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { 
                    it.write(builder.toString().toByteArray())
                }
            }
            _importStatus.postValue("Backup realizado com sucesso!")
        } catch (e: Exception) {
            _importStatus.postValue("Erro ao realizar backup: ${e.localizedMessage}")
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }
}
