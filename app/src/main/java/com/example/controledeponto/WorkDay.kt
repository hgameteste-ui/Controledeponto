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
    val clockOut: LocalTime? = null
) {
    fun calculateTotalMinutes(isToday: Boolean = false): Long {
        val start = clockIn ?: return 0L
        val now = LocalTime.now()
        
        return if (breakStart != null) {
            // Período 1: Entrada até Início do Intervalo
            val p1 = Duration.between(start, breakStart).toMinutes()
            
            // Período 2: Fim do Intervalo até Saída (ou Agora)
            val p2 = if (breakEnd != null) {
                val end = clockOut ?: (if (isToday) now else breakEnd)
                if (end.isAfter(breakEnd)) {
                    Duration.between(breakEnd, end).toMinutes()
                } else 0L
            } else 0L
            
            (p1 + p2).coerceAtLeast(0)
        } else {
            // Caso não tenha registrado intervalo (ou ainda não chegou nele)
            val end = clockOut ?: (if (isToday) now else start)
            Duration.between(start, end).toMinutes().coerceAtLeast(0)
        }
    }

    fun calculateBreakMinutes(isToday: Boolean = false): Long {
        val start = breakStart ?: return 0L
        val end = breakEnd ?: if (isToday) LocalTime.now() else start
        return Duration.between(start, end).toMinutes().coerceAtLeast(0)
    }
}
