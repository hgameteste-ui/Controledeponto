/**
 * Nome: IntervalDao.kt
 * Data: 24/05/2024
 * Hora: 10:10
 * Descrição: Interface DAO para gerenciar os intervalos de trabalho na tabela 'intervals'.
 * Histórico: 
 * - 24/05/2024: Criação inicial com métodos CRUD básicos.
 * - 24/05/2024: Adicionado getAllIntervals para cálculos globais.
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

    @Query("SELECT * FROM intervals WHERE workDayId = :date ORDER BY startTime ASC")
    fun getIntervalsByDate(date: LocalDate): Flow<List<WorkInterval>>

    @Query("SELECT * FROM intervals ORDER BY startTime ASC")
    fun getAllIntervals(): Flow<List<WorkInterval>>

    @Query("SELECT * FROM intervals WHERE id = :id")
    suspend fun getIntervalById(id: Long): WorkInterval?
}
