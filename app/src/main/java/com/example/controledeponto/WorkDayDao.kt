package com.example.controledeponto

import androidx.lifecycle.LiveData
import androidx.room.*
import java.time.LocalDate

@Dao
interface WorkDayDao {
    @Query("SELECT * FROM work_days WHERE date = :date")
    suspend fun getWorkDaySync(date: LocalDate): WorkDay?

    @Query("SELECT * FROM work_days WHERE date = :date")
    fun getWorkDay(date: LocalDate): LiveData<WorkDay?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workDay: WorkDay)

    @Update
    suspend fun update(workDay: WorkDay)

    @Query("SELECT * FROM work_days ORDER BY date DESC")
    fun getAllWorkDays(): LiveData<List<WorkDay>>

    @Query("SELECT * FROM work_days ORDER BY date ASC")
    suspend fun getAllWorkDaysSync(): List<WorkDay>

    @Query("SELECT * FROM work_days WHERE isHolidayOrOffDay = 1 ORDER BY date ASC")
    fun getHolidays(): LiveData<List<WorkDay>>
}
