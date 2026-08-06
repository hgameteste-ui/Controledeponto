/**
 * Nome: WorkInterval.kt
 * Data: 24/05/2024
 * Hora: 10:00
 * Descrição: Entidade que representa um intervalo de trabalho (pausa, almoço, etc) associado a um dia.
 * Histórico: 
 * - 24/05/2024: Criação inicial da entidade e enum IntervalType.
 */

package com.example.controledeponto

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

enum class IntervalType {
    BREAK, LUNCH, OTHER
}

@Entity(
    tableName = "intervals",
    foreignKeys = [
        ForeignKey(
            entity = WorkDay::class,
            parentColumns = ["date"],
            childColumns = ["workDayId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workDayId"])]
)
data class WorkInterval(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workDayId: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime? = null,
    val type: IntervalType = IntervalType.BREAK
)
