/*
 * Nome: WorkDay.kt
 * Versão: 1.2.0
 * Data: 25/05/2024
 * Hora: 16:00
 * Descrição: Entidade que representa um dia de trabalho. 
 * Atualizada para garantir que o total de horas trabalhadas desconte corretamente todos os intervalos (novos e legados).
 * 
 * Histórico de Modificações:
 * 25/05/2024 16:00 - Corrigida a lógica de cálculo de minutos para somar intervalos novos e legados em vez de tratá-los como exclusivos.
 * 25/05/2024 16:00 - Atualizada predição de saída para considerar a soma total de pausas.
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
    val holidayName: String? = null
) {
    /**
     * Calcula o total de minutos trabalhados no dia.
     * Fórmula: (Saída - Entrada) - Soma(intervalos_novos + intervalo_legado)
     */
    fun calculateTotalMinutes(intervals: List<WorkInterval> = emptyList(), isToday: Boolean = false): Long {
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
        val start = breakStart?.truncatedTo(ChronoUnit.MINUTES) ?: return 0L
        val end = (breakEnd ?: if (isToday) LocalTime.now() else start).truncatedTo(ChronoUnit.MINUTES)
        return ChronoUnit.MINUTES.between(start, end).coerceAtLeast(0)
    }

    /**
     * Predição do próximo evento baseada na jornada meta.
     * Considera todos os intervalos registrados para empurrar a hora de saída.
     */
    fun getNextPrediction(targetMinutes: Long, intervals: List<WorkInterval>): Pair<String, LocalTime>? {
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return null
        if (clockOut != null) return null

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
