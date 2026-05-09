package com.example.controledeponto

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class WorkViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: WorkRepository
    val todayWorkDay: LiveData<WorkDay?>
    val allWorkDays: LiveData<List<WorkDay>>

    init {
        val workDayDao = AppDatabase.getDatabase(application).workDayDao()
        repository = WorkRepository(workDayDao)
        todayWorkDay = repository.getWorkDay(LocalDate.now())
        allWorkDays = repository.allWorkDays
    }

    fun punchClock() = viewModelScope.launch {
        val today = LocalDate.now()
        val now = LocalTime.now()
        val current = repository.getWorkDaySync(today) ?: WorkDay(today)

        val updated = when {
            current.clockIn == null -> current.copy(clockIn = now)
            current.breakStart == null -> current.copy(breakStart = now)
            current.breakEnd == null -> current.copy(breakEnd = now)
            current.clockOut == null -> current.copy(clockOut = now)
            else -> current // Already finished for today
        }

        repository.insert(updated)
    }

    fun updateWorkDay(workDay: WorkDay) = viewModelScope.launch {
        repository.insert(workDay)
    }
}
