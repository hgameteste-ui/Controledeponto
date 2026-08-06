/*
 * Nome: WorkRepository.kt
 * Versão: 1.3.0
 * Data: 25/05/2024
 * Hora: 21:00
 * Descrição: Repositório que centraliza o acesso aos dados de WorkDay e WorkInterval.
 * 
 * Histórico de Modificações:
 * 24/05/2024 21:30 - Adicionados métodos para buscar WorkDayWithIntervals para garantir cálculos precisos.
 * 25/05/2024 21:00 - Adicionado método deleteWorkDay para permitir a exclusão completa de um dia.
 */

package com.example.controledeponto

import androidx.lifecycle.LiveData
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class WorkRepository(
    private val workDayDao: WorkDayDao,
    private val intervalDao: IntervalDao
) {
    val allWorkDays: LiveData<List<WorkDay>> = workDayDao.getAllWorkDays()
    val allWorkDaysWithIntervals: LiveData<List<WorkDayWithIntervals>> = workDayDao.getAllWorkDaysWithIntervals()
    
    fun getWorkDay(date: LocalDate): LiveData<WorkDay?> = workDayDao.getWorkDay(date)
    
    fun getWorkDayWithIntervals(date: LocalDate): LiveData<WorkDayWithIntervals?> = workDayDao.getWorkDayWithIntervals(date)
    
    suspend fun getWorkDaySync(date: LocalDate): WorkDay? = workDayDao.getWorkDaySync(date)
    
    suspend fun getAllWorkDaysSync(): List<WorkDay> = workDayDao.getAllWorkDaysSync()

    suspend fun getAllWorkDaysWithIntervalsSync(): List<WorkDayWithIntervals> = workDayDao.getAllWorkDaysWithIntervalsSync()
    
    fun getHolidays(): LiveData<List<WorkDay>> = workDayDao.getHolidays()

    suspend fun insert(workDay: WorkDay) = workDayDao.insert(workDay)

    suspend fun deleteWorkDay(workDay: WorkDay) = workDayDao.delete(workDay)

    // Intervalos
    fun getIntervalsByDate(date: LocalDate): Flow<List<WorkInterval>> = intervalDao.getIntervalsByDate(date)
    
    fun getAllIntervals(): Flow<List<WorkInterval>> = intervalDao.getAllIntervals()
    
    suspend fun insertInterval(interval: WorkInterval) = intervalDao.insert(interval)
    
    suspend fun updateInterval(interval: WorkInterval) = intervalDao.update(interval)

    suspend fun deleteInterval(interval: WorkInterval) = intervalDao.delete(interval)
}
