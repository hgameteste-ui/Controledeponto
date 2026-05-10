package com.example.controledeponto

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.nio.charset.Charset

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

    private fun calculateTotal(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        return list.filter { it.date.month == selectedDate.month && it.date.year == selectedDate.year }
            .sumOf { it.calculateTotalMinutes(isToday = it.date == now) }
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
        _importStatus.postValue("Importando dados...")
        var count = 0
        val errorLogs = mutableListOf<String>()

        try {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext
                
                // Tenta UTF-8, se falhar tenta Windows-1252 (comum em CSVs do Excel no Brasil)
                val content = try {
                    val decoder = Charsets.UTF_8.newDecoder()
                    decoder.decode(java.nio.ByteBuffer.wrap(bytes))
                    String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    String(bytes, Charset.forName("Windows-1252"))
                }
                
                val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

                content.lines().forEachIndexed { index, rawLine ->
                    val line = rawLine.trim().replace("\uFEFF", "")
                    if (index == 0 || line.isBlank()) return@forEachIndexed 
                    
                    // Usa ponto e vírgula como separador conforme seu exemplo
                    val parts = line.split(";").map { it.trim().removeSurrounding("\"").trim() }
                    
                    if (parts.size >= 5) {
                        try {
                            // Limpa a data: "qua., 01/04/26" -> extrai "01/04/26"
                            val rawDate = parts[0]
                            val cleanDateStr = if (rawDate.contains(",")) {
                                rawDate.split(",")[1].trim()
                            } else {
                                rawDate
                            }

                            val date = LocalDate.parse(cleanDateStr, dateFormatter)
                            
                            fun parseTime(s: String?): LocalTime? {
                                if (s.isNullOrBlank()) return null
                                return try { LocalTime.parse(s, timeFormatter) } catch (e: Exception) { null }
                            }

                            val clockIn = parseTime(parts.getOrNull(1))
                            // Só importa se houver pelo menos o horário de entrada
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
                            if (errorLogs.size < 3) errorLogs.add("Linha ${index + 1}: ${e.message}")
                        }
                    }
                }
            }
            
            val resultMsg = if (count > 0) {
                "Sucesso! $count registros importados." + (if (errorLogs.isNotEmpty()) "\n\nErros:\n${errorLogs.joinToString("\n")}" else "")
            } else {
                "Nenhum registro importado. Verifique se o separador é ';' e a data está no formato '01/04/26'."
            }
            _importStatus.postValue(resultMsg)
            
        } catch (e: Exception) {
            _importStatus.postValue("Falha ao abrir arquivo: ${e.localizedMessage}")
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }
}
