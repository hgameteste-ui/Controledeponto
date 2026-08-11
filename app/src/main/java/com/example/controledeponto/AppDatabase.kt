/**
 * Nome: AppDatabase.kt
 * Data: 12/02/2025
 * Hora: 18:15
 * Descrição: Classe abstrata que define o banco de dados Room e suas migrações.
 * Atualizada para versão 5 com suporte a migração e fallback.
 */

package com.example.controledeponto

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [WorkDay::class, WorkInterval::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDayDao(): WorkDayDao
    abstract fun intervalDao(): IntervalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Adiciona a coluna isAbsence como INTEGER (Boolean no Room) com valor padrão 0 (false)
                db.execSQL("ALTER TABLE `work_days` ADD COLUMN `isAbsence` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "work_day_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration() // Garante que o app abra mesmo se a migração falhar
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
