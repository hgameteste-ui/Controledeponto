/**
 * Nome: IntervalDao.kt
 * Data: 13/02/2025
 * Hora: 19:15
 * Descrição: Interface DAO para gerenciar os intervalos de trabalho.
 * Atualizada: Adicionado método para exclusão por data para facilitar importação.
 */

package com.example.controledeponto

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface IntervalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interval: WorkInterval): Long

    @Update
    suspend fun update(interval: WorkInterval)

    @Delete
    suspend fun delete(interval: WorkInterval)

    @Query("DELETE FROM intervals WHERE workDayId = :date")
    suspend fun deleteIntervalsByDate(date: LocalDate)

    @Query("SELECT * FROM intervals WHERE workDayId = :date ORDER BY startTime ASC")
    fun getIntervalsByDate(date: LocalDate): Flow<List<WorkInterval>>

    @Query("SELECT * FROM intervals ORDER BY startTime ASC")
    fun getAllIntervals(): Flow<List<WorkInterval>>

    @Query("SELECT * FROM intervals WHERE id = :id")
    suspend fun getIntervalById(id: Long): WorkInterval?
}
