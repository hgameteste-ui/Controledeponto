/*
 * Nome: WorkDayDao.kt
 * Versão: 1.6.0
 * Data: 25/05/2024
 * Hora: 18:00
 * Descrição: Interface DAO para gerenciar registros de dias de trabalho.
 * 
 * Histórico de Modificações:
 * 24/05/2024 21:15 - Adicionados métodos para buscar WorkDayWithIntervals para garantir contabilização correta de pausas.
 * 24/05/2024 21:45 - Substituído @Insert(REPLACE) por @Upsert para preservar intervalos relacionados.
 * 25/05/2024 14:00 - Atualizada documentação e garantida integridade dos métodos de busca agregada.
 * 25/05/2024 18:00 - Revisão final e atualização de cabeçalho para suporte a edição de intervalos.
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
