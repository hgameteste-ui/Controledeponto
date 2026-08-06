/**
 * Nome: AppDatabase.kt
 * Data: 24/05/2024
 * Hora: 10:20
 * Descrição: Classe abstrata que define o banco de dados Room e suas migrações.
 * Histórico: 
 * - 24/05/2024: Atualização para versão 4, adição da tabela 'intervals' e migration MIGRATION_3_4.
 */

package com.example.controledeponto

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [WorkDay::class, WorkInterval::class], version = 4, exportSchema = false)
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "work_day_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(1, 2) // Mantém flexibilidade se necessário, mas 3->4 tem migration
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
