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
 * Regras de Negócio Implementadas:
 * 1. Cálculo Líquido: Permite saldos diários e acumulados negativos (débitos de horas).
 * 2. Proteção de Dias Futuros: Evita a computação de metas para datas posteriores ao dia atual.
 * 3. Tratamento do Dia Corrente: Zera a meta diária para o dia de hoje caso o primeiro ponto
 *    ainda não tenha sido batido, evitando exibir saldos negativos falsos antes do expediente.
 * 4. Fins de Semana e Folgas: Sábados, domingos ou dias explicitamente marcados com a flag
 *    [isHolidayOrOffDay] possuem meta padrão de 0 minutos.
 *
 * Histórico de Modificações:
 * Versão   Data        Autor           Descrição
 * -----------------------------------------------------------------------------------------
 * 1.8.5    Jun/2026    Walter R. C.    Ajuste nos métodos de cálculo para ignorar a meta diária
 *                                      caso o dia seja sinalizado como feriado ou folga [isHolidayOrOffDay].
 * 1.8.3    Jun/2026    Walter R. C.    Blindagem do método calculateOvertime para evitar vazamento
 *                                      de minutos negativos no painel quando onlySurplus é true.
 * 1.8.1    Jun/2026    Walter R. C.    Refatoração dos métodos calculateQuarterly para suportar
 *                                      saldos negativos e inclusão de tratamento de faltas.
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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

    // Total trabalhado no mês selecionado (bruto)
    val monthlyTotalMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateTotal(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateTotal(allWorkDays.value, date) }
    }

    // Soma das horas EXTRAS puras do mês selecionado
    val monthlyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = true) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDays.value, date, onlySurplus = true) }
    }

    // Saldo líquido mensal do mês selecionado (Banco de horas: Extra - Débito)
    val monthlyBalanceMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, _selectedDate.value, onlySurplus = false) }
        addSource(_selectedDate) { date -> value = calculateOvertime(allWorkDays.value, date, onlySurplus = false) }
    }

    // Dias úteis no mês selecionado (excluindo sáb e dom)
    val monthlyBusinessDays: LiveData<Int> = _selectedDate.map { date ->
        calculateBusinessDays(date)
    }

    // Dias úteis RESTANTES no mês selecionado (do dia selecionado em diante, se for mês atual/futuro)
    val remainingBusinessDays: LiveData<Int> = _selectedDate.map { date ->
        calculateRemainingBusinessDays(date)
    }

    // Horas EXTRAS diárias sugeridas para atingir a meta mensal
    val suggestedDailyOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        val update = {
            val overtime = monthlyOvertimeMinutes.value ?: 0L
            val remaining = remainingBusinessDays.value ?: 0
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val goalHours = prefs.getString("monthly_goal", "30")?.toLongOrNull() ?: 30L

            val goalMinutes = goalHours * 60

            if (remaining > 0) {
                val neededExtraTotal = (goalMinutes - overtime).coerceAtLeast(0L)
                value = neededExtraTotal / remaining
            } else {
                value = 0L
            }
        }
        addSource(monthlyOvertimeMinutes) { update() }
        addSource(remainingBusinessDays) { update() }
    }

    // Projeção de horas extras para o final do mês baseado no ritmo atual
    val extrapolatedOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        val update = {
            val overtime = monthlyOvertimeMinutes.value ?: 0L
            val totalBusiness = monthlyBusinessDays.value ?: 0
            val remaining = remainingBusinessDays.value ?: 0

            // Dias úteis que já passaram (considerando hoje como decorrido para a média)
            val elapsed = (totalBusiness - remaining + 1).coerceAtLeast(1)

            // Projeção: (Extras totais / dias decorridos) * total de dias do mês
            value = (overtime.toDouble() / elapsed * totalBusiness).toLong()
        }
        addSource(monthlyOvertimeMinutes) { update() }
        addSource(monthlyBusinessDays) { update() }
        addSource(remainingBusinessDays) { update() }
    }

    // Horas EXTRAS do MÊS CORRENTE (Hoje) - Fixo para o Dashboard de Progresso
    val currentMonthOvertimeMinutes: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateOvertime(list, LocalDate.now(), onlySurplus = true) }
    }

    // Soma acumulada de horas EXTRAS desde o início do trimestre até o mês selecionado
    val quarterlyOvertimeAccumulated: LiveData<Long> = MediatorLiveData<Long>().apply {
        addSource(allWorkDays) { list -> value = calculateQuarterlyAccumulated(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateQuarterlyAccumulated(allWorkDays.value, date) }
    }

    // Horas EXTRAS por mês dentro do trimestre atual
    val quarterlyMonthlyOvertime: LiveData<List<Pair<String, Long>>> = MediatorLiveData<List<Pair<String, Long>>>().apply {
        addSource(allWorkDays) { list -> value = calculateQuarterlyMonthly(list, _selectedDate.value) }
        addSource(_selectedDate) { date -> value = calculateQuarterlyMonthly(allWorkDays.value, date) }
    }

    private fun calculateBusinessDays(date: LocalDate): Int {
        var count = 0
        val daysInMonth = date.lengthOfMonth()
        for (i in 1..daysInMonth) {
            val d = date.withDayOfMonth(i)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                count++
            }
        }
        return count
    }

    private fun calculateRemainingBusinessDays(selectedDate: LocalDate): Int {
        val now = LocalDate.now()
        // Se o mês selecionado já passou
        if (selectedDate.year < now.year || (selectedDate.year == now.year && selectedDate.monthValue < now.monthValue)) {
            return 0
        }

        val startDay = if (selectedDate.month == now.month && selectedDate.year == now.year) {
            now.dayOfMonth
        } else {
            1
        }

        val daysInMonth = selectedDate.lengthOfMonth()
        var count = 0
        for (i in startDay..daysInMonth) {
            val d = selectedDate.withDayOfMonth(i)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                count++
            }
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
            it.date.month == targetDate.month &&
                    it.date.year == targetDate.year &&
                    (!onlySurplus || it.clockIn != null) &&
                    !it.date.isAfter(now)
        }.sumOf { day ->
            val worked = day.calculateTotalMinutes(isToday = day.date == now)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY

            // REGRA ATUALIZADA: Feriado/Folga ou Fim de Semana derruba a meta para 0
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) {
                0L
            } else {
                dailyGoalMinutes
            }

            val diff = worked - effectiveGoal

            if (onlySurplus) {
                diff.coerceAtLeast(0L)
            } else {
                diff
            }
        }
    }

    private fun calculateQuarterlyAccumulated(list: List<WorkDay>?, selectedDate: LocalDate?): Long {
        if (list == null || selectedDate == null) return 0L
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val dailyGoalMinutes = workHours * 60

        val startMonth = ((selectedDate.monthValue - 1) / 3) * 3 + 1

        return list.filter {
            it.date.year == selectedDate.year &&
                    it.date.monthValue >= startMonth &&
                    it.date.monthValue <= selectedDate.monthValue &&
                    !it.date.isAfter(now)
        }.sumOf { day ->
            val worked = day.calculateTotalMinutes(isToday = day.date == now)
            val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY

            // REGRA ATUALIZADA: Considera isHolidayOrOffDay no acúmulo trimestral
            val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) {
                0L
            } else {
                dailyGoalMinutes
            }

            worked - effectiveGoal
        }
    }

    private fun calculateQuarterlyMonthly(list: List<WorkDay>?, selectedDate: LocalDate?): List<Pair<String, Long>> {
        if (list == null || selectedDate == null) return emptyList()
        val now = LocalDate.now()
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val workHours = prefs.getString("work_hours", "8")?.toLong() ?: 8L
        val dailyGoalMinutes = workHours * 60

        val startMonthValue = ((selectedDate.monthValue - 1) / 3) * 3 + 1
        val result = mutableListOf<Pair<String, Long>>()

        for (m in startMonthValue..selectedDate.monthValue) {
            val monthTotal = list.filter {
                it.date.year == selectedDate.year &&
                        it.date.monthValue == m &&
                        !it.date.isAfter(now)
            }.sumOf { day ->
                val worked = day.calculateTotalMinutes(isToday = day.date == now)
                val isWeekend = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY

                // REGRA ATUALIZADA: Considera isHolidayOrOffDay na separação mensal do gráfico
                val effectiveGoal = if (isWeekend || day.isHolidayOrOffDay || (day.date == now && day.clockIn == null)) {
                    0L
                } else {
                    dailyGoalMinutes
                }

                worked - effectiveGoal
            }
            val monthName = Month.of(m).getDisplayName(TextStyle.FULL, Locale("pt", "BR"))
                .replaceFirstChar { it.uppercase() }
            result.add(monthName to monthTotal)
        }
        return result
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
                                    clockOut = parseTime(parts.getOrNull(4)),
                                    isHolidayOrOffDay = false
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