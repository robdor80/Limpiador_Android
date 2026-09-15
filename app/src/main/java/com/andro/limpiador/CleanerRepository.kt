package com.andro.limpiador

import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class Candidate(
    val uri: Uri,
    val canonicalPath: String,
    val name: String,
    val sourceFolder: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val sizeBytes: Long,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val preview: List<String> = emptyList()
)

data class AnalysisResult(
    val candidates: List<Candidate>,
    val analyzedFolders: Int,
    val unavailableFolders: Int
)

data class SelectedRoots(val valid: List<File>, val unavailable: Int)

class CleanerRepository {
    suspend fun analyze(days: Int, excludedPaths: Set<String>, selectedFolderPaths: Set<String>): AnalysisResult = withContext(Dispatchers.IO) {
        val roots = resolveSelectedDirectories(selectedFolderPaths)
        // If roots overlap, the outer root already covers the nested one.
        val analysisRoots = roots.valid.filter { root ->
            roots.valid.none { other -> other != root && isInsideActiveFolder(root, other) }
        }
        val candidatesByPath = linkedMapOf<String, Candidate>()
        var analyzedFolders = 0
        var unavailableFolders = roots.unavailable
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        analysisRoots.forEach rootLoop@ { root ->
            val children = root.listFiles()
            if (children == null) {
                unavailableFolders++
                return@rootLoop
            }
            analyzedFolders++
            children.forEach childLoop@ { file ->
                if (!isDirectChild(file, root)) return@childLoop
                val canonicalFile = runCatching { file.canonicalFile }.getOrElse { return@childLoop }
                if (canonicalFile.path in candidatesByPath || canonicalFile.path in excludedPaths) return@childLoop
                val modified = file.lastModified()
                if (modified <= 0L || modified >= cutoff) return@childLoop
                val sourceFolder = displayRoot(root)
                val candidate = when {
                    file.isDirectory -> {
                        val summary = summarize(file, root)
                        Candidate(Uri.fromFile(canonicalFile), canonicalFile.path, file.name.ifBlank { "(sin nombre)" }, sourceFolder, true, modified, summary.size, summary.files, summary.folders, summary.preview)
                    }
                    file.isFile -> Candidate(Uri.fromFile(canonicalFile), canonicalFile.path, file.name.ifBlank { "(sin nombre)" }, sourceFolder, false, modified, file.length().coerceAtLeast(0L))
                    else -> null
                }
                if (candidate != null) candidatesByPath[canonicalFile.path] = candidate
            }
        }
        AnalysisResult(
            candidatesByPath.values.sortedWith(compareByDescending<Candidate> { it.isDirectory }.thenBy { it.name.lowercase() }),
            analyzedFolders,
            unavailableFolders
        )
    }

    suspend fun deleteSelected(selectedUris: Set<Uri>, selectedFolderPaths: Set<String>): DeleteResult = withContext(Dispatchers.IO) {
        val roots = resolveSelectedDirectories(selectedFolderPaths).valid
        data class Target(val file: File, val root: File)
        val allowed = linkedMapOf<Uri, Target>()
        roots.forEach { root ->
            root.listFiles()?.forEach childLoop@ { file ->
                if (!isDirectChild(file, root)) return@childLoop
                runCatching { file.canonicalFile }.getOrNull()?.let { canonical ->
                    allowed.putIfAbsent(Uri.fromFile(canonical), Target(canonical, root))
                }
            }
        }
        var deleted = 0
        var failed = 0
        var freedBytes = 0L
        selectedUris.forEach { uri ->
            val target = allowed[uri]
            val sizeBeforeDelete = target?.file?.length()?.coerceAtLeast(0L) ?: 0L
            if (target == null) failed++ else if (deleteSafely(target.file, target.root)) {
                deleted++
                freedBytes += sizeBeforeDelete
            } else failed++
        }
        DeleteResult(deleted, failed, freedBytes)
    }

    private data class Summary(val size: Long, val files: Int, val folders: Int, val preview: List<String>)

    private fun summarize(folder: File, root: File): Summary {
        var size = 0L; var files = 0; var folders = 0
        val children = folder.listFiles()?.toList() ?: return Summary(0, 0, 0, listOf("No se pudo leer"))
        val preview = children.take(5).map { it.name.ifBlank { "(sin nombre)" } }
        fun visit(item: File) {
            if (!isInsideActiveFolder(item, root)) return
            if (item.isDirectory) {
                folders++
                item.listFiles()?.forEach(::visit)
            } else if (item.isFile) { files++; size += item.length().coerceAtLeast(0L) }
        }
        children.forEach(::visit)
        return Summary(size, files, folders, preview)
    }

    private fun deleteSafely(file: File, root: File): Boolean {
        if (!isInsideActiveFolder(file, root)) return false
        if (file.isDirectory) {
            val children = file.listFiles()?.toList() ?: return false
            if (children.any { !deleteSafely(it, root) }) return false
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun isDirectChild(file: File, root: File): Boolean =
        runCatching { file.canonicalFile.parentFile == root }.getOrDefault(false)

    private fun isInsideActiveFolder(file: File, root: File): Boolean = runCatching {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    fun activeDirectory(activeFolderPath: String?): File? {
        val candidate = if (activeFolderPath == null) downloadsDirectory() else File(activeFolderPath)
        return selectableDirectory(candidate)
    }

    fun resolveSelectedDirectories(paths: Set<String>): SelectedRoots {
        val valid = linkedMapOf<String, File>()
        var unavailable = 0
        paths.forEach { path ->
            val directory = selectableDirectory(File(path))
            if (directory == null) unavailable++ else valid[directory.path] = directory
        }
        return SelectedRoots(valid.values.toList(), unavailable)
    }

    fun sharedStorageRoot(): File? = runCatching {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        root.takeIf { it.isDirectory && it.canRead() }
    }.getOrNull()

    fun browsableDirectory(directory: File): File? = runCatching {
        val root = sharedStorageRoot() ?: return@runCatching null
        val canonical = directory.canonicalFile
        canonical.takeIf { it.isDirectory && it.canRead() && (canonical == root || isInsideActiveFolder(canonical, root)) }
    }.getOrNull()

    fun selectableDirectory(directory: File): File? = runCatching {
        val root = sharedStorageRoot() ?: return@runCatching null
        val canonical = directory.canonicalFile
        canonical.takeIf { it.isDirectory && it.canRead() && it.canWrite() && canonical != root && isInsideActiveFolder(canonical, root) }
    }.getOrNull()

    fun childDirectories(directory: File): List<File> = browsableDirectory(directory)
        ?.listFiles()
        ?.mapNotNull(::browsableDirectory)
        ?.filter { it != sharedStorageRoot() }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    private fun displayRoot(root: File): String {
        val storage = sharedStorageRoot() ?: return root.name
        return root.path.removePrefix(storage.path).trimStart(File.separatorChar).ifEmpty { root.name }
    }

    @Suppress("DEPRECATION")
    private fun downloadsDirectory(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}

data class DeleteResult(val deleted: Int, val failed: Int, val freedBytes: Long = 0L)
