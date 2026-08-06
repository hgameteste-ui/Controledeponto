/*
 * Nome: WorkDayWithIntervals.kt
 * Versão: 1.0.0
 * Data: 24/05/2024
 * Hora: 21:00
 * Descrição: POJO para representar a relação um-para-muitos entre WorkDay e WorkInterval.
 * 
 * Histórico de Modificações:
 * 24/05/2024 21:00 - Criação inicial para corrigir falha na contabilização de intervalos.
 */

package com.example.controledeponto

import androidx.room.Embedded
import androidx.room.Relation

data class WorkDayWithIntervals(
    @Embedded val workDay: WorkDay,
    @Relation(
        parentColumn = "date",
        entityColumn = "workDayId"
    )
    val intervals: List<WorkInterval>
) {
    /**
     * Atalho para calcular o total de minutos usando os intervalos reais.
     */
    fun calculateTotalMinutes(isToday: Boolean = false): Long {
        return workDay.calculateTotalMinutes(intervals, isToday)
    }
}
