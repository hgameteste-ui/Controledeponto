package com.example.controledeponto

import androidx.lifecycle.LiveData
import java.time.LocalDate

class WorkRepository(private val workDayDao: WorkDayDao) {
    val allWorkDays: LiveData<List<WorkDay>> = workDayDao.getAllWorkDays()

    fun getWorkDay(date: LocalDate): LiveData<WorkDay?> {
        return workDayDao.getWorkDay(date)
    }

    suspend fun insert(workDay: WorkDay) {
        workDayDao.insert(workDay)
    }

    suspend fun getWorkDaySync(date: LocalDate): WorkDay? {
        return workDayDao.getWorkDaySync(date)
    }
}
