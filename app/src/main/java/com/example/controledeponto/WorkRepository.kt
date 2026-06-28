package com.example.controledeponto

import androidx.lifecycle.LiveData
import java.time.LocalDate

class WorkRepository(private val workDayDao: WorkDayDao) {
    val allWorkDays: LiveData<List<WorkDay>> = workDayDao.getAllWorkDays()
    fun getWorkDay(date: LocalDate): LiveData<WorkDay?> = workDayDao.getWorkDay(date)
    suspend fun getWorkDaySync(date: LocalDate): WorkDay? = workDayDao.getWorkDaySync(date)
    suspend fun getAllWorkDaysSync(): List<WorkDay> = workDayDao.getAllWorkDaysSync()
    suspend fun insert(workDay: WorkDay) = workDayDao.insert(workDay)
    fun getHolidays(): LiveData<List<WorkDay>> = workDayDao.getHolidays()
}
