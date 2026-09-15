package com.andro.limpiador

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class CleanerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = settingsRepo.settings.first()
        if (!settings.automaticEnabled || settings.mode != CleanMode.AUTOMATIC || settings.treeUri == null) return Result.success()
        return runCatching {
            val uri = Uri.parse(settings.treeUri)
            val candidates = CleanerRepository(applicationContext).analyze(uri, settings.days)
            // Safety rule: scheduled cleanup never removes folders.
            val files = candidates.filter { !it.isDirectory }.map { it.uri }.toSet()
            val result = CleanerRepository(applicationContext).deleteSelected(uri, files)
            if (result.deleted > 0) settingsRepo.markCleanup()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
