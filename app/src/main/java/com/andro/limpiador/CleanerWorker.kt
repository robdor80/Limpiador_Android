package com.andro.limpiador

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class CleanerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = settingsRepo.settings.first()
        if (!settings.automaticEnabled) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showNotification("Análisis programado no realizado", "Falta el permiso de almacenamiento.")
            return Result.success()
        }
        val repository = CleanerRepository()
        val roots = repository.resolveSelectedDirectories(settings.selectedFolderPaths)
        if (settings.selectedFolderPaths.isEmpty() || roots.valid.isEmpty()) {
            showNotification("Análisis programado no realizado", "No hay carpetas seleccionadas disponibles.")
            return Result.success()
        }
        return runCatching {
            val analysis = repository.analyze(settings.days, settings.exclusions, settings.selectedFolderPaths)
            val candidates = analysis.candidates
            val folders = candidates.count { it.isDirectory }
            val period = if (settings.frequency == Frequency.DAILY) "diaria" else "semanal"
            if (settings.mode == CleanMode.AUTOMATIC) {
                // Safety rule: scheduled cleanup never removes folders.
                val files = candidates.filter { !it.isDirectory }.map { it.uri }.toSet()
                val result = repository.deleteSelected(files, settings.selectedFolderPaths)
                if (result.deleted > 0) settingsRepo.markCleanup()
                showNotification("Limpieza $period completada", automaticResultText(result, folders, analysis.unavailableFolders))
            } else {
                showNotification(
                    "Análisis $period completado",
                    reviewResultText(candidates.count { !it.isDirectory }, folders, analysis.unavailableFolders)
                )
            }
            Result.success()
        }.getOrElse {
            showNotification("Análisis programado no realizado", "No se pudo completar el análisis.")
            Result.retry()
        }
    }

    private fun automaticResultText(result: DeleteResult, folders: Int, unavailable: Int): String {
        val parts = mutableListOf<String>()
        if (result.deleted == 0) {
            parts += "No había archivos para eliminar"
        } else {
            val fileText = if (result.deleted == 1) "1 archivo eliminado" else "${result.deleted} archivos eliminados"
            parts += "$fileText · ${formatBytes(result.freedBytes)} liberados"
        }
        if (folders > 0) parts += pendingFoldersText(folders)
        if (unavailable > 0) parts += unavailableFoldersText(unavailable)
        return parts.joinToString(" · ")
    }

    private fun reviewResultText(files: Int, folders: Int, unavailable: Int): String {
        val parts = mutableListOf<String>()
        if (files > 0) parts += if (files == 1) "1 archivo pendiente de revisión" else "$files archivos pendientes de revisión"
        if (folders > 0) parts += pendingFoldersText(folders)
        if (unavailable > 0) parts += unavailableFoldersText(unavailable)
        return parts.joinToString(" · ").ifEmpty { "No había elementos para revisar" }
    }

    private fun pendingFoldersText(folders: Int): String =
        if (folders == 1) "1 carpeta pendiente de revisión" else "$folders carpetas pendientes de revisión"

    private fun unavailableFoldersText(folders: Int): String =
        if (folders == 1) "1 carpeta no disponible" else "$folders carpetas no disponibles"

    private fun showNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Análisis programado", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private companion object {
        const val CHANNEL_ID = "scheduled_analysis"
        const val NOTIFICATION_ID = 1001
    }
}
