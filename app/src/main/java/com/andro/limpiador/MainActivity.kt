package com.andro.limpiador

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { CleanerApp() } }
    }
}

@Composable
private fun CleanerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = remember { SettingsRepository(context.applicationContext) }
    val cleanerRepo = remember { CleanerRepository() }
    val settings by settingsRepo.settings.collectAsState(initial = CleanerSettings())
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmFolders by remember { mutableStateOf(false) }
    var hasStorageAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    LaunchedEffect(settings.automaticEnabled, settings.frequency, settings.mode) {
        configurePeriodicCleanup(context, settings)
    }

    val accessSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasStorageAccess = hasAllFilesAccess()
        status = if (hasStorageAccess) "Acceso a Descargas concedido." else "Falta conceder acceso a todos los archivos."
    }

    fun removeSelection() {
        scope.launch {
            val result = cleanerRepo.deleteSelected(selected)
            if (result.deleted > 0) settingsRepo.markCleanup()
            status = "Eliminados: ${result.deleted}. Errores o elementos conservados: ${result.failed}."
            candidates = candidates.filterNot { it.uri in selected }
            selected = emptySet()
        }
    }

    if (confirmFolders) {
        AlertDialog(
            onDismissRequest = { confirmFolders = false },
            title = { Text("Eliminar carpetas seleccionadas") },
            text = { Text("Las carpetas seleccionadas y todo su contenido se eliminarán permanentemente. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { confirmFolders = false; removeSelection() }) { Text("Eliminar carpetas") } },
            dismissButton = { TextButton(onClick = { confirmFolders = false }) { Text("Cancelar") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("LIMPIADOR DE DESCARGAS", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Carpeta")
                    Text("Descargas")
                }
                if (!hasStorageAccess) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val appIntent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { accessSettingsLauncher.launch(appIntent) }.onFailure {
                                accessSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }
                    }) { Text("Conceder acceso") }
                }
            }
            Text(if (hasStorageAccess) "Acceso concedido" else "Se necesita acceso a todos los archivos")
        }
        item {
            OutlinedTextField(
                value = settings.days.toString(),
                onValueChange = { value -> value.toIntOrNull()?.takeIf { it > 0 }?.let { scope.launch { settingsRepo.setDays(it) } } },
                label = { Text("Eliminar archivos con más de días") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text("Modo")
            ModeRow("Revisar antes de eliminar", settings.mode == CleanMode.REVIEW) { scope.launch { settingsRepo.setMode(CleanMode.REVIEW) } }
            ModeRow("Eliminar automáticamente", settings.mode == CleanMode.AUTOMATIC) { scope.launch { settingsRepo.setMode(CleanMode.AUTOMATIC) } }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Limpieza automática")
                Switch(checked = settings.automaticEnabled, onCheckedChange = { scope.launch { settingsRepo.setAutomatic(it) } })
            }
            Text("Frecuencia")
            ModeRow("Diaria", settings.frequency == Frequency.DAILY) { scope.launch { settingsRepo.setFrequency(Frequency.DAILY) } }
            ModeRow("Semanal", settings.frequency == Frequency.WEEKLY) { scope.launch { settingsRepo.setFrequency(Frequency.WEEKLY) } }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                hasStorageAccess = hasAllFilesAccess()
                if (!hasStorageAccess) { status = "Concede primero acceso a todos los archivos."; return@Button }
                scope.launch {
                    runCatching { cleanerRepo.analyze(settings.days) }.onSuccess { found ->
                        settingsRepo.markAnalysis()
                        candidates = found
                        if (settings.mode == CleanMode.AUTOMATIC) {
                            val files = found.filter { !it.isDirectory }.map { it.uri }.toSet()
                            val result = cleanerRepo.deleteSelected(files)
                            if (result.deleted > 0) settingsRepo.markCleanup()
                            candidates = found.filter { it.isDirectory }
                            selected = emptySet()
                            status = "Automático: eliminados ${result.deleted} archivos. Las carpetas se han conservado para revisión."
                        } else {
                            selected = found.map { it.uri }.toSet()
                            status = "Análisis completado: ${found.size} elementos candidatos."
                        }
                    }.onFailure { status = "No se pudo analizar la carpeta: ${it.message ?: "error de acceso"}" }
                }
            }) { Text("ANALIZAR AHORA") }
        }
        item {
            Text("Último análisis: ${formatDate(settings.lastAnalysis)}")
            Text("Última limpieza: ${formatDate(settings.lastCleanup)}")
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        if (settings.mode == CleanMode.REVIEW && candidates.isNotEmpty()) {
            item {
                val selectedItems = candidates.filter { it.uri in selected }
                Text("Seleccionados: ${selectedItems.size} · Se liberará: ${formatBytes(selectedItems.sumOf { it.sizeBytes })}")
            }
            items(candidates, key = { it.uri.toString() }) { candidate ->
                CandidateRow(candidate, candidate.uri in selected) { checked ->
                    selected = if (checked) selected + candidate.uri else selected - candidate.uri
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = emptySet() }) { Text("Cancelar") }
                    Button(enabled = selected.isNotEmpty(), onClick = {
                        if (candidates.any { it.uri in selected && it.isDirectory }) confirmFolders = true else removeSelection()
                    }) { Text("Eliminar seleccionados") }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected = checked, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun CandidateRow(candidate: Candidate, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp)) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Column(Modifier.padding(start = 6.dp)) {
                Text(candidate.name)
                val age = ((System.currentTimeMillis() - candidate.lastModified) / TimeUnit.DAYS.toMillis(1)).coerceAtLeast(0)
                if (candidate.isDirectory) {
                    Text("Carpeta · ${formatBytes(candidate.sizeBytes)} · ${candidate.fileCount} archivos · ${candidate.folderCount} subcarpetas · $age días")
                    Text("Contenido: ${candidate.preview.joinToString(", ")}${if (candidate.preview.size >= 5) ", ..." else ""}")
                } else {
                    val extension = candidate.name.substringAfterLast('.', "sin extensión")
                    val extensionLabel = if (extension == "sin extensión") extension else ".$extension"
                    Text("$extensionLabel · ${formatBytes(candidate.sizeBytes)} · $age días")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatDate(value: Long?): String = value?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Nunca"

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun configurePeriodicCleanup(context: android.content.Context, settings: CleanerSettings) {
    val workManager = WorkManager.getInstance(context)
    if (!settings.automaticEnabled || settings.mode != CleanMode.AUTOMATIC) {
        workManager.cancelUniqueWork("periodic_cleanup")
        return
    }
    val interval = if (settings.frequency == Frequency.DAILY) 1L else 7L
    val request = PeriodicWorkRequestBuilder<CleanerWorker>(interval, TimeUnit.DAYS).build()
    workManager.enqueueUniquePeriodicWork("periodic_cleanup", ExistingPeriodicWorkPolicy.UPDATE, request)
}
