/*
 * Nome: WorkDay.kt
 * Versão: 1.3.0
 * Data: 12/02/2025
 * Hora: 17:00
 * Descrição: Entidade que representa um dia de trabalho. 
 * Atualizada para incluir sinalização de falta (isAbsence).
 * 
 * Histórico de Modificações:
 * 25/05/2024 16:00 - Corrigida a lógica de cálculo de minutos para somar intervalos novos e legados.
 * 12/02/2025 17:00 - Adicionado campo isAbsence para permitir contabilizar faltas no saldo de horas.
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
    val breakStart: LocalTime? = null,
    val breakEnd: LocalTime? = null,
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
        
        // Soma os intervalos da nova tabela
        val intervalsBreakMinutes = intervals.sumOf { interval ->
            val iStart = interval.startTime.truncatedTo(ChronoUnit.MINUTES)
            val iEnd = (interval.endTime ?: if (isToday) now else iStart).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(iStart, iEnd)
        }
        
        // Soma o intervalo do sistema legado (campos breakStart/End)
        val legacyBreakMinutes = calculateBreakMinutes(isToday)
        
        val totalBreakMinutes = intervalsBreakMinutes + legacyBreakMinutes
        
        return (totalGross - totalBreakMinutes).coerceAtLeast(0)
    }

    /**
     * Calcula o total de minutos em pausa usando os campos legados.
     */
    fun calculateBreakMinutes(isToday: Boolean = false): Long {
        if (isAbsence) return 0L
        val start = breakStart?.truncatedTo(ChronoUnit.MINUTES) ?: return 0L
        val end = (breakEnd ?: if (isToday) LocalTime.now() else start).truncatedTo(ChronoUnit.MINUTES)
        return ChronoUnit.MINUTES.between(start, end).coerceAtLeast(0)
    }

    /**
     * Predição do próximo evento baseada na jornada meta.
     * Considera todos os intervalos registrados para empurrar a hora de saída.
     */
    fun getNextPrediction(targetMinutes: Long, intervals: List<WorkInterval>): Pair<String, LocalTime>? {
        if (isAbsence || clockOut != null) return null
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return null
        
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        
        // Se houver qualquer intervalo ativo (novo ou legado), não há predição de saída
        val activeInterval = intervals.find { it.endTime == null }
        if (activeInterval != null) return null
        if (breakStart != null && breakEnd == null) return null

        val intervalsBreakMinutes = intervals.sumOf { 
            val iStart = it.startTime.truncatedTo(ChronoUnit.MINUTES)
            val iEnd = (it.endTime ?: now).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(iStart, iEnd)
        }

        val legacyBreakMinutes = calculateBreakMinutes(true)
        val effectiveBreaks = intervalsBreakMinutes + legacyBreakMinutes

        return "Saída" to start.plusMinutes(targetMinutes).plusMinutes(effectiveBreaks)
    }
}
