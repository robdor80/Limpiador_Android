package com.andro.limpiador

import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class Candidate(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val sizeBytes: Long,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val preview: List<String> = emptyList()
)

class CleanerRepository {
    suspend fun analyze(days: Int): List<Candidate> = withContext(Dispatchers.IO) {
        val root = downloadsDirectory()
        if (!root.isDirectory) return@withContext emptyList()
        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { return@withContext emptyList() }
        val children = root.listFiles() ?: return@withContext emptyList()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        children.mapNotNull { file ->
            if (!isDirectChild(file, rootCanonical)) return@mapNotNull null
            // A missing/invalid timestamp is uncertain: preserve the item.
            val modified = file.lastModified()
            if (modified <= 0L || modified >= cutoff) return@mapNotNull null
            if (file.isDirectory) {
                val summary = summarize(file, rootCanonical)
                Candidate(Uri.fromFile(file), file.name.ifBlank { "(sin nombre)" }, true, modified, summary.size, summary.files, summary.folders, summary.preview)
            } else if (file.isFile) {
                Candidate(Uri.fromFile(file), file.name.ifBlank { "(sin nombre)" }, false, modified, file.length().coerceAtLeast(0L))
            } else null
        }.sortedWith(compareByDescending<Candidate> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun deleteSelected(selectedUris: Set<Uri>): DeleteResult = withContext(Dispatchers.IO) {
        val root = downloadsDirectory()
        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { return@withContext DeleteResult(0, selectedUris.size) }
        val allowed = root.listFiles()?.filter { isDirectChild(it, rootCanonical) }?.associateBy { Uri.fromFile(it) }
            ?: return@withContext DeleteResult(0, selectedUris.size)
        var deleted = 0
        var failed = 0
        selectedUris.forEach { uri ->
            val target = allowed[uri]
            if (target == null) failed++ else if (deleteSafely(target, rootCanonical)) deleted++ else failed++
        }
        DeleteResult(deleted, failed)
    }

    private data class Summary(val size: Long, val files: Int, val folders: Int, val preview: List<String>)

    private fun summarize(folder: File, root: File): Summary {
        var size = 0L; var files = 0; var folders = 0
        val children = folder.listFiles()?.toList() ?: return Summary(0, 0, 0, listOf("No se pudo leer"))
        val preview = children.take(5).map { it.name.ifBlank { "(sin nombre)" } }
        fun visit(item: File) {
            if (!isInsideDownloads(item, root)) return
            if (item.isDirectory) {
                folders++
                item.listFiles()?.forEach(::visit)
            } else if (item.isFile) { files++; size += item.length().coerceAtLeast(0L) }
        }
        children.forEach(::visit)
        return Summary(size, files, folders, preview)
    }

    private fun deleteSafely(file: File, root: File): Boolean {
        if (!isInsideDownloads(file, root)) return false
        if (file.isDirectory) {
            val children = file.listFiles()?.toList() ?: return false
            if (children.any { !deleteSafely(it, root) }) return false
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun isDirectChild(file: File, root: File): Boolean =
        runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)

    private fun isInsideDownloads(file: File, root: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun downloadsDirectory(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}

data class DeleteResult(val deleted: Int, val failed: Int)
