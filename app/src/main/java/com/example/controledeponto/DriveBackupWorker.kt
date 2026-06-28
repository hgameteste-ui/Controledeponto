package com.example.controledeponto

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class DriveBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val PREFS_NAME = "backup_prefs"
        const val PREF_LAST_BACKUP_DATE = "PREF_LAST_BACKUP_DATE"
        const val PREF_LAST_BACKUP_FILE = "PREF_LAST_BACKUP_FILE"
        const val PREF_LAST_BACKUP_STATUS = "PREF_LAST_BACKUP_STATUS"
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = LocalDateTime.now()
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
        val currentDateTime = now.format(dateFormatter)
        
        val fileName = "backup_geral_${now.format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HHmm"))}.csv"

        return try {
            // A lógica de backup real seria implementada aqui
            // Simulando sucesso para diagnóstico dos Logs
            
            Log.d("BACKUP_DEBUG", "Gravando sucesso no Worker. Arquivo: " + fileName + " | Data: " + currentDateTime)
            
            prefs.edit().apply {
                putString(PREF_LAST_BACKUP_DATE, currentDateTime)
                putString(PREF_LAST_BACKUP_FILE, fileName)
                putString(PREF_LAST_BACKUP_STATUS, "SUCESSO")
                apply()
            }

            Result.success()
        } catch (exception: Exception) {
            Log.e("BACKUP_DEBUG", "Erro crítico dentro do Worker: ", exception)
            prefs.edit().apply {
                putString(PREF_LAST_BACKUP_STATUS, "FALHA")
                apply()
            }
            Result.failure()
        }
    }
}
