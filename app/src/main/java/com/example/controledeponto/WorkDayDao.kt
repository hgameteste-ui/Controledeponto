/*
 * Nome: WorkDayDao.kt
 * Versão: 2.1.0
 * Data: 12/02/2025
 * Hora: 20:15
 * Descrição: Interface DAO para gerenciar registros de dias de trabalho.
 * Atualizada: Utilizando @Upsert para evitar a exclusão em cascata de intervalos ao atualizar feriados.
 */

package com.example.controledeponto

import androidx.lifecycle.LiveData
import androidx.room.*
import java.time.LocalDate

@Dao
interface WorkDayDao {
    @Query("SELECT * FROM work_days WHERE date = :date")
    suspend fun getWorkDaySync(date: LocalDate): WorkDay?

    @Query("SELECT * FROM work_days WHERE date = :date")
    fun getWorkDay(date: LocalDate): LiveData<WorkDay?>

    @Transaction
    @Query("SELECT * FROM work_days WHERE date = :date")
    fun getWorkDayWithIntervals(date: LocalDate): LiveData<WorkDayWithIntervals?>

    @Upsert
    suspend fun insert(workDay: WorkDay)

    @Update
    suspend fun update(workDay: WorkDay)

    @Delete
    suspend fun delete(workDay: WorkDay)

    @Transaction
    @Query("SELECT * FROM work_days ORDER BY date DESC")
    fun getAllWorkDaysWithIntervals(): LiveData<List<WorkDayWithIntervals>>

    @Transaction
    @Query("SELECT * FROM work_days ORDER BY date ASC")
    suspend fun getAllWorkDaysWithIntervalsSync(): List<WorkDayWithIntervals>

    @Query("SELECT * FROM work_days ORDER BY date DESC")
    fun getAllWorkDays(): LiveData<List<WorkDay>>

    @Query("SELECT * FROM work_days ORDER BY date ASC")
    suspend fun getAllWorkDaysSync(): List<WorkDay>

    @Query("SELECT * FROM work_days WHERE isHolidayOrOffDay = 1 ORDER BY date ASC")
    fun getHolidays(): LiveData<List<WorkDay>>
}
