package com.andro.limpiador

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class CleanerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = settingsRepo.settings.first()
        if (!settings.automaticEnabled || settings.mode != CleanMode.AUTOMATIC) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return Result.success()
        return runCatching {
            val repository = CleanerRepository()
            val candidates = repository.analyze(settings.days)
            // Safety rule: scheduled cleanup never removes folders.
            val files = candidates.filter { !it.isDirectory }.map { it.uri }.toSet()
            val result = repository.deleteSelected(files)
            if (result.deleted > 0) settingsRepo.markCleanup()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
