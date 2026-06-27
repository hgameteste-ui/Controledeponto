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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

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
    fun calculateTotalMinutes(isToday: Boolean = false): Long {
        val start = clockIn ?: return 0L
        val now = LocalTime.now()

        return if (breakStart != null) {
            val p1 = Duration.between(start, breakStart).toMinutes()
            val p2 = if (breakEnd != null) {
                val end = clockOut ?: (if (isToday) now else breakEnd)
                if (end.isAfter(breakEnd)) {
                    Duration.between(breakEnd, end).toMinutes()
                } else 0L
            } else 0L
            (p1 + p2).coerceAtLeast(0)
        } else {
            val end = clockOut ?: (if (isToday) now else start)
            Duration.between(start, end).toMinutes().coerceAtLeast(0)
        }
    }

    fun calculateBreakMinutes(isToday: Boolean = false): Long {
        val start = breakStart ?: return 0L
        val end = breakEnd ?: if (isToday) LocalTime.now() else start
        return Duration.between(start, end).toMinutes().coerceAtLeast(0)
    }

    fun getNextPrediction(targetMinutes: Long, breakMinutes: Long): Pair<String, LocalTime>? {
        return when {
            clockIn == null -> null
            breakStart == null -> "Início do Intervalo" to clockIn.plusHours(4)
            breakEnd == null -> "Fim do Intervalo" to breakStart.plusMinutes(breakMinutes)
            clockOut == null -> {
                val workedBeforeBreak = Duration.between(clockIn, breakStart).toMinutes()
                val remaining = targetMinutes - workedBeforeBreak
                "Saída" to breakEnd.plusMinutes(remaining)
            }
            else -> null
        }
    }
}
