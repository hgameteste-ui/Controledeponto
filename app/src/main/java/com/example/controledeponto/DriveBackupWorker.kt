package com.example.controledeponto

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = LocalDateTime.now()
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
        val currentDateTime = now.format(dateFormatter)
        val fileName = "backup_auto_${now.format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HHmm"))}.csv"

        try {
            // 1. Verificar se o usuário está logado no Google
            val account = GoogleSignIn.getLastSignedInAccount(applicationContext)
            if (account == null) {
                Log.e("BACKUP_DEBUG", "Usuário não está logado no Google. Backup abortado.")
                return@withContext Result.failure()
            }

            // 2. Instanciar Repositório e buscar dados
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = WorkRepository(database.workDayDao(), database.intervalDao())
            val allData = repository.getAllWorkDaysWithIntervalsSync()

            if (allData.isEmpty()) {
                Log.d("BACKUP_DEBUG", "Nenhum dado para backup.")
                return@withContext Result.success()
            }

            // 3. Gerar conteúdo CSV em um arquivo temporário
            val tempFile = File(applicationContext.cacheDir, fileName)
            val appPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val dailyGoalMinutes = (appPrefs.getString("work_hours", "8")?.toLong() ?: 8L) * 60
            val tf = DateTimeFormatter.ofPattern("HH:mm")
            val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val ptBr = Locale("pt", "BR")

            tempFile.bufferedWriter().use { writer ->
                writer.write("Data;Dia da Semana;Entrada;Início Intervalo;Fim Intervalo;Saída;Horas Trabalhadas;Meta;Saldo;Tipo\n")
                allData.forEach { item ->
                    val day = item.workDay
                    // item é do tipo WorkDayWithIntervals. calculateTotalMinutes(isToday)
                    val worked = item.calculateTotalMinutes(day.date == now.toLocalDate())
                    val isHoliday = day.isHolidayOrOffDay
                    val weightedWorked = if (isHoliday) worked * 2 else worked
                    val effectiveGoal = if (day.date.dayOfWeek.value > 5 || isHoliday) 0L else dailyGoalMinutes
                    val mainBreak = item.intervals.sortedBy { it.startTime }.firstOrNull()
                    
                    writer.write("${day.date.format(df)};${day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, ptBr)};${day.clockIn?.format(tf) ?: ""};${mainBreak?.startTime?.format(tf) ?: ""};${mainBreak?.endTime?.format(tf) ?: ""};${day.clockOut?.format(tf) ?: ""};$worked;$effectiveGoal;${weightedWorked - effectiveGoal};${if (isHoliday) "Feriado" else "Util"}\n")
                }
            }

            // 4. Configurar serviço do Google Drive
            val credential = GoogleAccountCredential.usingOAuth2(
                applicationContext, Collections.singleton(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccount = account.account }

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("Meu Ponto").build()

            // 5. Realizar o Upload
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                parents = Collections.singletonList("root")
            }
            val mediaContent = FileContent("text/csv", tempFile)
            
            driveService.files().create(fileMetadata, mediaContent).execute()

            // 6. Limpar arquivo temporário e salvar status
            tempFile.delete()

            Log.d("BACKUP_DEBUG", "Backup enviado para o Drive com sucesso: $fileName")
            
            prefs.edit().apply {
                putString(PREF_LAST_BACKUP_DATE, currentDateTime)
                putString(PREF_LAST_BACKUP_FILE, fileName)
                putString(PREF_LAST_BACKUP_STATUS, "SUCESSO")
                apply()
            }

            Result.success()

        } catch (e: Exception) {
            Log.e("BACKUP_DEBUG", "Erro crítico no backup: ", e)
            prefs.edit().apply {
                putString(PREF_LAST_BACKUP_STATUS, "FALHA")
                apply()
            }
            Result.retry()
        }
    }
}
