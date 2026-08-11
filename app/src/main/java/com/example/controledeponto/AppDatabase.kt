/*
 * Nome: AppDatabase.kt
 * Data: 13/02/2025
 * Hora: 12:00
 * Descrição: Classe abstrata que define o banco de dados Room e suas migrações.
 * Atualizada para versão 6: Migração do modelo híbrido para o modelo único de intervalos.
 */

package com.example.controledeponto

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [WorkDay::class, WorkInterval::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDayDao(): WorkDayDao
    abstract fun intervalDao(): IntervalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Transferir dados dos campos legados (breakStart/breakEnd) para a tabela de intervalos
                db.execSQL("""
                    INSERT INTO intervals (workDayId, startTime, endTime, type)
                    SELECT date, breakStart, breakEnd, 'LUNCH'
                    FROM work_days
                    WHERE breakStart IS NOT NULL
                """.trimIndent())

                // 2. Recriar a tabela work_days sem os campos legados
                // SQLite não suporta DROP COLUMN diretamente de forma simples em todas as versões
                db.execSQL("""
                    CREATE TABLE `work_days_new` (
                        `date` TEXT PRIMARY KEY NOT NULL, 
                        `clockIn` TEXT, 
                        `clockOut` TEXT, 
                        `isHolidayOrOffDay` INTEGER NOT NULL, 
                        `holidayName` TEXT, 
                        `isAbsence` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO work_days_new (date, clockIn, clockOut, isHolidayOrOffDay, holidayName, isAbsence)
                    SELECT date, clockIn, clockOut, isHolidayOrOffDay, holidayName, isAbsence FROM work_days
                """.trimIndent())

                db.execSQL("DROP TABLE work_days")
                db.execSQL("ALTER TABLE work_days_new RENAME TO work_days")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `work_days` ADD COLUMN `isAbsence` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `intervals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `workDayId` TEXT NOT NULL, 
                        `startTime` TEXT NOT NULL, 
                        `endTime` TEXT, 
                        `type` TEXT NOT NULL, 
                        FOREIGN KEY(`workDayId`) REFERENCES `work_days`(`date`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_intervals_workDayId` ON `intervals` (`workDayId`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "work_day_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
