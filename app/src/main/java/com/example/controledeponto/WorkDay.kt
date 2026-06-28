/**
 * Nome do Arquivo: WorkDay.kt
 * Pacote: com.example.controledeponto
 *
 * Descrição:
 * Entidade que representa um dia de trabalho no banco de dados Room.
 * Contém os horários de entrada, pausa e saída, além de flags de controle
 * para dias especiais como folgas e feriados.
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
    val holidayName: String? = null // NOVO CAMPO: Armazena o nome oficial do feriado
) {
    /**
     * Calcula o total de minutos trabalhados no dia.
     * Utiliza ChronoUnit.MINUTES.between para garantir precisão e arredondamento 
     * consistente, eliminando segundos e nanossegundos das batidas.
     */
    fun calculateTotalMinutes(isToday: Boolean = false): Long {
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return 0L
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)

        return if (breakStart != null) {
            val bStart = breakStart.truncatedTo(ChronoUnit.MINUTES)
            val p1 = ChronoUnit.MINUTES.between(start, bStart)
            val p2 = if (breakEnd != null) {
                val bEnd = breakEnd.truncatedTo(ChronoUnit.MINUTES)
                val end = (clockOut ?: (if (isToday) now else bEnd)).truncatedTo(ChronoUnit.MINUTES)
                if (end.isAfter(bEnd)) {
                    ChronoUnit.MINUTES.between(bEnd, end)
                } else 0L
            } else 0L
            (p1 + p2).coerceAtLeast(0)
        } else {
            val end = (clockOut ?: (if (isToday) now else start)).truncatedTo(ChronoUnit.MINUTES)
            ChronoUnit.MINUTES.between(start, end).coerceAtLeast(0)
        }
    }

    /**
     * Calcula o total de minutos em pausa.
     */
    fun calculateBreakMinutes(isToday: Boolean = false): Long {
        val start = breakStart?.truncatedTo(ChronoUnit.MINUTES) ?: return 0L
        val end = (breakEnd ?: if (isToday) LocalTime.now() else start).truncatedTo(ChronoUnit.MINUTES)
        return ChronoUnit.MINUTES.between(start, end).coerceAtLeast(0)
    }

    /**
     * Predição do próximo evento baseada na jornada meta.
     */
    fun getNextPrediction(targetMinutes: Long, breakMinutes: Long): Pair<String, LocalTime>? {
        val start = clockIn?.truncatedTo(ChronoUnit.MINUTES) ?: return null
        val bStart = breakStart?.truncatedTo(ChronoUnit.MINUTES)
        val bEnd = breakEnd?.truncatedTo(ChronoUnit.MINUTES)

        return when {
            breakStart == null -> "Início do Intervalo" to start.plusHours(4)
            breakEnd == null -> "Fim do Intervalo" to bStart!!.plusMinutes(breakMinutes)
            clockOut == null -> {
                val workedBeforeBreak = ChronoUnit.MINUTES.between(start, bStart!!)
                val remaining = targetMinutes - workedBeforeBreak
                "Saída" to bEnd!!.plusMinutes(remaining)
            }
            else -> null
        }
    }
}
