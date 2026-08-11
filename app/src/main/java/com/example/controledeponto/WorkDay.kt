/*
 * Nome: WorkDay.kt
 * Versão: 1.4.0
 * Data: 13/02/2025
 * Hora: 11:00
 * Descrição: Entidade que representa um dia de trabalho.
 * Removidos campos legados de intervalo para usar apenas a lista WorkInterval.
 */

package com.example.controledeponto

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@Entity(tableName = "work_days")
data class WorkDay(
    @PrimaryKey val date: LocalDate = LocalDate.now(),
    val clockIn: LocalTime? = null,
    val clockOut: LocalTime? = null,
    val isHolidayOrOffDay: Boolean = false,
    val holidayName: String? = null,
    val isAbsence: Boolean = false
) {
    /**
     * Calcula o total de minutos trabalhados no dia.
     * Se for falta, retorna 0.
     */
    fun calculateTotalMinutes(intervals: List<WorkInterval> = emptyList(), isToday: Boolean = false): Long {
        if (isAbsence) return 0L
        
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return 0L
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val end = (clockOut ?: (if (isToday) now else start)).truncatedTo(ChronoUnit.MINUTES)
        
        val totalGross = ChronoUnit.MINUTES.between(start, end)
        
        // Soma os intervalos da tabela de intervalos
        val totalBreakMinutes = intervals.sumOf { interval ->
            val iStart = interval.startTime.truncatedTo(ChronoUnit.MINUTES)
            val iEnd = (interval.endTime ?: if (isToday) now else iStart).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(iStart, iEnd)
        }
        
        return (totalGross - totalBreakMinutes).coerceAtLeast(0)
    }

    /**
     * Calcula o total de minutos em pausa.
     */
    fun calculateBreakMinutes(intervals: List<WorkInterval>, isToday: Boolean = false): Long {
        if (isAbsence) return 0L
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        return intervals.sumOf { interval ->
            val iStart = interval.startTime.truncatedTo(ChronoUnit.MINUTES)
            val iEnd = (interval.endTime ?: if (isToday) now else iStart).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(iStart, iEnd)
        }.coerceAtLeast(0)
    }

    /**
     * Predição do próximo evento baseada na jornada meta.
     * Considera todos os intervalos registrados para empurrar a hora de saída.
     */
    fun getNextPrediction(targetMinutes: Long, intervals: List<WorkInterval>): Pair<String, LocalTime>? {
        if (isAbsence || clockOut != null) return null
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return null
        
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        
        // Se houver qualquer intervalo ativo, não há predição de saída
        val activeInterval = intervals.find { it.endTime == null }
        if (activeInterval != null) return null

        val totalBreakMinutes = intervals.sumOf { 
            val iStart = it.startTime.truncatedTo(ChronoUnit.MINUTES)
            val iEnd = (it.endTime ?: now).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(iStart, iEnd)
        }

        return "Saída" to start.plusMinutes(targetMinutes).plusMinutes(totalBreakMinutes)
    }
}
