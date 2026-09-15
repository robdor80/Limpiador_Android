package com.andro.limpiador

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class CleanerRepository(private val context: Context) {
    suspend fun analyze(treeUri: Uri, days: Int): List<Candidate> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        root.listFiles().mapNotNull { file ->
            // A missing/invalid timestamp is uncertain: preserve the item.
            val modified = file.lastModified()
            if (modified <= 0L || modified >= cutoff) return@mapNotNull null
            if (file.isDirectory) {
                val summary = summarize(file)
                Candidate(file.uri, file.name ?: "(sin nombre)", true, modified, summary.size, summary.files, summary.folders, summary.preview)
            } else if (file.isFile) {
                Candidate(file.uri, file.name ?: "(sin nombre)", false, modified, file.length().coerceAtLeast(0L))
            } else null
        }.sortedWith(compareByDescending<Candidate> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun deleteSelected(treeUri: Uri, selectedUris: Set<Uri>): DeleteResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext DeleteResult(0, selectedUris.size)
        // Only direct children of the currently authorized tree can be deleted.
        val allowed = root.listFiles().associateBy { it.uri }
        var deleted = 0
        var failed = 0
        selectedUris.forEach { uri ->
            val target = allowed[uri]
            if (target == null) failed++ else if (deleteSafely(target)) deleted++ else failed++
        }
        DeleteResult(deleted, failed)
    }

    private data class Summary(val size: Long, val files: Int, val folders: Int, val preview: List<String>)

    private fun summarize(folder: DocumentFile): Summary {
        var size = 0L; var files = 0; var folders = 0
        val children = runCatching { folder.listFiles().toList() }.getOrElse { return Summary(0, 0, 0, listOf("No se pudo leer")) }
        val preview = children.take(5).map { it.name ?: "(sin nombre)" }
        fun visit(item: DocumentFile) {
            if (item.isDirectory) {
                folders++
                runCatching { item.listFiles().forEach(::visit) }
            } else if (item.isFile) { files++; size += item.length().coerceAtLeast(0L) }
        }
        children.forEach(::visit)
        return Summary(size, files, folders, preview)
    }

    private fun deleteSafely(file: DocumentFile): Boolean {
        if (file.isDirectory) {
            val children = runCatching { file.listFiles().toList() }.getOrElse { return false }
            if (children.any { !deleteSafely(it) }) return false
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }
}

data class DeleteResult(val deleted: Int, val failed: Int)
