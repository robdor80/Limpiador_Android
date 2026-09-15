package com.andro.limpiador

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmFolders by remember { mutableStateOf(false) }
    var showExclusions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var pickerDirectory by remember { mutableStateOf<File?>(null) }
    var draftSelectedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var analyzedFolderCount by remember { mutableStateOf(0) }
    var unavailableFolderCount by remember { mutableStateOf(0) }
    var daysText by remember { mutableStateOf(settings.days.toString()) }
    val selectedRoots = cleanerRepo.resolveSelectedDirectories(settings.selectedFolderPaths)
    val activeExclusions = settings.exclusions.filter { path ->
        selectedRoots.valid.any { folder -> exclusionBelongsToFolder(path, folder) }
    }
    val storageRoot = cleanerRepo.sharedStorageRoot()
    val selectedFolderLabels = settings.selectedFolderPaths.sorted().map { displayFolderPath(File(it), storageRoot) }

    LaunchedEffect(Unit) {
        settingsRepo.migrateSelectedFoldersIfNeeded()
    }

    LaunchedEffect(settings.days) {
        if (daysText != settings.days.toString()) daysText = settings.days.toString()
    }

    fun commitDays(): Int {
        val days = daysText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        daysText = days.toString()
        scope.launch { settingsRepo.setDays(days) }
        return days
    }
    var hasStorageAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    LaunchedEffect(settings.automaticEnabled, settings.frequency, settings.mode) {
        configurePeriodicCleanup(context, settings)
    }

    val accessSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasStorageAccess = hasAllFilesAccess()
        status = if (hasStorageAccess) "Acceso al almacenamiento concedido." else "Falta conceder acceso a todos los archivos."
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun removeSelection() {
        scope.launch {
            val result = cleanerRepo.deleteSelected(selected, settings.selectedFolderPaths)
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

    if (showExclusions) {
        AlertDialog(
            onDismissRequest = { showExclusions = false },
            title = { Text("Exclusiones (${activeExclusions.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (activeExclusions.isEmpty()) Text("No hay archivos excluidos en las carpetas seleccionadas.")
                    activeExclusions.sorted().forEach { path ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(File(path).name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { settingsRepo.removeExclusion(path) } }) { Text("Quitar exclusión") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExclusions = false }) { Text("Cerrar") } }
        )
    }

    if (showFolderPicker) {
        val root = cleanerRepo.sharedStorageRoot()
        val current = pickerDirectory?.let(cleanerRepo::browsableDirectory) ?: root
        val folders = current?.let(cleanerRepo::childDirectories).orEmpty()
        AlertDialog(
            onDismissRequest = { showFolderPicker = false },
            title = { Text("Gestionar carpetas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${draftSelectedFolders.size} ${if (draftSelectedFolders.size == 1) "carpeta seleccionada" else "carpetas seleccionadas"}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(current?.let { displayFolderPath(it, root) } ?: "Almacenamiento no disponible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val parent = current?.parentFile?.let(cleanerRepo::browsableDirectory)
                    TextButton(enabled = parent != null, onClick = { pickerDirectory = parent }) { Text("↑ Carpeta superior") }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        if (folders.isEmpty()) item { Text("No hay carpetas disponibles.", style = MaterialTheme.typography.bodySmall) }
                        items(folders, key = { it.path }) { folder ->
                            val selectablePath = cleanerRepo.selectableDirectory(folder)?.path
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selectablePath != null && selectablePath in draftSelectedFolders,
                                    enabled = selectablePath != null,
                                    onCheckedChange = { checked ->
                                        if (selectablePath != null) {
                                            draftSelectedFolders = if (checked) draftSelectedFolders + selectablePath else draftSelectedFolders - selectablePath
                                        }
                                    }
                                )
                                TextButton(modifier = Modifier.weight(1f), onClick = { pickerDirectory = folder }) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(folder.name.ifBlank { "(sin nombre)" })
                                        Text(">")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { settingsRepo.setSelectedFolders(draftSelectedFolders) }
                    candidates = emptyList()
                    selected = emptySet()
                    analyzedFolderCount = 0
                    unavailableFolderCount = 0
                    status = if (draftSelectedFolders.isEmpty()) "No hay carpetas seleccionadas." else "Selección de carpetas guardada."
                    showFolderPicker = false
                }) { Text("Guardar selección") }
            },
            dismissButton = { TextButton(onClick = { showFolderPicker = false }) { Text("Cancelar") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Limpiador de descargas", style = MaterialTheme.typography.titleLarge)
                Box {
                    TextButton(onClick = { showMenu = true }) { Text("☰") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Gestionar carpetas") }, onClick = {
                            pickerDirectory = cleanerRepo.sharedStorageRoot()
                            draftSelectedFolders = settings.selectedFolderPaths
                            showMenu = false
                            showFolderPicker = true
                        })
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Carpetas analizadas · ${settings.selectedFolderPaths.size}", style = MaterialTheme.typography.titleMedium)
                            selectedFolderLabels.take(3).forEach { label ->
                                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            if (selectedFolderLabels.size > 3) {
                                Text("+${selectedFolderLabels.size - 3} más", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Surface(
                                color = if (settings.selectedFolderPaths.isNotEmpty() && selectedRoots.valid.isNotEmpty() && hasStorageAccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    when {
                                        !hasStorageAccess -> "Acceso necesario"
                                        settings.selectedFolderPaths.isEmpty() -> "Sin carpetas seleccionadas"
                                        selectedRoots.valid.isEmpty() -> "No hay carpetas disponibles"
                                        selectedRoots.unavailable > 0 -> "${selectedRoots.unavailable} ${if (selectedRoots.unavailable == 1) "carpeta no disponible" else "carpetas no disponibles"}"
                                        else -> "Acceso concedido"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (settings.selectedFolderPaths.isNotEmpty() && selectedRoots.valid.isNotEmpty() && hasStorageAccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
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
                    TextButton(onClick = { showExclusions = true }, modifier = Modifier.padding(top = 1.dp)) {
                        Text("Gestionar exclusiones · ${activeExclusions.size}")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Eliminar archivos con más de", style = MaterialTheme.typography.bodyMedium)
                        Text("Antigüedad mínima", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { value ->
                            if (value.all { it in '0'..'9' }) {
                                daysText = value
                                value.toIntOrNull()?.let { scope.launch { settingsRepo.setDays(it) } }
                            }
                        },
                        label = { Text("Días") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            commitDays()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }),
                        modifier = Modifier.width(82.dp).onFocusChanged { if (!it.isFocused) commitDays() }
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text("Al analizar", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    AnalysisModeOption("Revisar antes de eliminar", settings.mode == CleanMode.REVIEW) { scope.launch { settingsRepo.setMode(CleanMode.REVIEW) } }
                    AnalysisModeOption("Eliminar archivos directamente", settings.mode == CleanMode.AUTOMATIC) { scope.launch { settingsRepo.setMode(CleanMode.AUTOMATIC) } }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Análisis programado", style = MaterialTheme.typography.titleSmall)
                            Text("Ejecutar automáticamente en segundo plano", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = settings.automaticEnabled, onCheckedChange = { enabled ->
                            scope.launch { settingsRepo.setAutomatic(enabled) }
                            if (
                                enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                                !settings.notificationPermissionAsked
                            ) {
                                scope.launch { settingsRepo.setNotificationPermissionAsked() }
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        })
                    }
                    Text(
                        "Frecuencia",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (settings.automaticEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModeRow("Diaria", settings.frequency == Frequency.DAILY, settings.automaticEnabled) { scope.launch { settingsRepo.setFrequency(Frequency.DAILY) } }
                    ModeRow("Semanal", settings.frequency == Frequency.WEEKLY, settings.automaticEnabled) { scope.launch { settingsRepo.setFrequency(Frequency.WEEKLY) } }
                }
            }
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val daysForAnalysis = commitDays()
                hasStorageAccess = hasAllFilesAccess()
                if (!hasStorageAccess) { status = "Concede primero acceso a todos los archivos."; return@Button }
                if (settings.selectedFolderPaths.isEmpty()) { status = "No hay carpetas seleccionadas."; return@Button }
                if (selectedRoots.valid.isEmpty()) { status = "No hay carpetas seleccionadas disponibles."; return@Button }
                scope.launch {
                    runCatching { cleanerRepo.analyze(daysForAnalysis, settings.exclusions, settings.selectedFolderPaths) }.onSuccess { analysis ->
                        val found = analysis.candidates
                        settingsRepo.markAnalysis()
                        analyzedFolderCount = analysis.analyzedFolders
                        unavailableFolderCount = analysis.unavailableFolders
                        candidates = found
                        if (settings.mode == CleanMode.AUTOMATIC) {
                            val files = found.filter { !it.isDirectory }.map { it.uri }.toSet()
                            val result = cleanerRepo.deleteSelected(files, settings.selectedFolderPaths)
                            if (result.deleted > 0) settingsRepo.markCleanup()
                            candidates = found.filter { it.isDirectory }
                            selected = emptySet()
                            status = "Automático: eliminados ${result.deleted} archivos. ${analysis.analyzedFolders} carpetas analizadas." +
                                unavailableAnalysisText(analysis.unavailableFolders)
                        } else {
                            // Files are selected by default; folders always require an explicit user choice.
                            selected = found.filter { !it.isDirectory }.map { it.uri }.toSet()
                            status = "Análisis completado: ${found.size} elementos candidatos en ${analysis.analyzedFolders} carpetas." +
                                unavailableAnalysisText(analysis.unavailableFolders)
                        }
                    }.onFailure { status = "No se pudieron analizar las carpetas: ${it.message ?: "error de acceso"}" }
                }
            }) { Text("Analizar ahora") }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Último análisis · ${formatDate(settings.lastAnalysis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Última limpieza · ${formatDate(settings.lastCleanup)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.takeIf { candidates.isEmpty() }?.let {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        if (settings.mode == CleanMode.REVIEW && candidates.isNotEmpty()) {
            item {
                val selectedItems = candidates.filter { it.uri in selected }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text("Análisis completado", style = MaterialTheme.typography.titleSmall)
                        Text("${candidates.size} ${if (candidates.size == 1) "candidato" else "candidatos"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            "$analyzedFolderCount ${if (analyzedFolderCount == 1) "carpeta analizada" else "carpetas analizadas"}" +
                                if (unavailableFolderCount > 0) " · $unavailableFolderCount no disponibles" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                                Text("${selectedItems.size} seleccionados", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                                Text("${formatBytes(selectedItems.sumOf { it.sizeBytes })} por liberar", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            items(candidates, key = { it.uri.toString() }) { candidate ->
                CandidateRow(candidate, candidate.uri in selected, onKeep = {
                    scope.launch { settingsRepo.addExclusion(candidate.canonicalPath) }
                    candidates = candidates.filterNot { it.uri == candidate.uri }
                    selected = selected - candidate.uri
                }) { checked ->
                    selected = if (checked) selected + candidate.uri else selected - candidate.uri
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { candidates = emptyList(); selected = emptySet(); analyzedFolderCount = 0; unavailableFolderCount = 0; status = null }) { Text("Cancelar") }
                    Button(modifier = Modifier.weight(1.4f), enabled = selected.isNotEmpty(), onClick = {
                        if (candidates.any { it.uri in selected && it.isDirectory }) confirmFolders = true else removeSelection()
                    }) { Text("Eliminar seleccionados") }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected = checked, enabled = enabled, onClick = if (enabled) onClick else null)
        Text(label, modifier = Modifier.padding(start = 6.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnalysisModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun CandidateRow(candidate: Candidate, checked: Boolean, onKeep: () -> Unit, onChecked: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            TypeBadge(if (candidate.isDirectory) "C" else fileTypeLabel(candidate.name).take(1))
            Column(Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
                Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                Text(candidate.sourceFolder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                val age = ((System.currentTimeMillis() - candidate.lastModified) / TimeUnit.DAYS.toMillis(1)).coerceAtLeast(0)
                if (candidate.isDirectory) {
                    Text("Carpeta · $age días", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${formatBytes(candidate.sizeBytes)} · ${candidate.fileCount} archivos · ${candidate.folderCount} subcarpetas", style = MaterialTheme.typography.bodySmall)
                    Text("Contenido", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(candidate.preview.joinToString(", ").ifEmpty { "—" } + if (candidate.preview.size >= 5) ", ..." else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${fileTypeLabel(candidate.name)} · $age días", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(candidate.sizeBytes), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onKeep, modifier = Modifier.padding(top = 2.dp)) { Text("Conservar siempre") }
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(label: String) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private fun fileTypeLabel(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "PDF"
    "jpg", "jpeg", "png", "webp", "gif" -> "Imagen"
    "doc", "docx", "txt", "odt" -> "Documento"
    else -> "Archivo"
}

private fun exclusionBelongsToFolder(path: String, folder: File): Boolean = runCatching {
    path.startsWith(folder.canonicalPath + File.separator)
}.getOrDefault(false)

private fun displayFolderPath(folder: File, root: File?): String {
    if (root == null) return folder.name
    val relative = folder.path.removePrefix(root.path).trimStart(File.separatorChar)
    return relative.ifEmpty { "Almacenamiento compartido" }
}

private fun unavailableAnalysisText(count: Int): String = when (count) {
    0 -> ""
    1 -> " 1 carpeta no disponible."
    else -> " $count carpetas no disponibles."
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatDate(value: Long?): String {
    if (value == null) return "Nunca"
    val dateTime = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale("es", "ES")))
    return if (dateTime.toLocalDate() == LocalDate.now()) {
        "Hoy, $time"
    } else {
        "${dateTime.format(DateTimeFormatter.ofPattern("d MMM", Locale("es", "ES")))}, $time"
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun configurePeriodicCleanup(context: android.content.Context, settings: CleanerSettings) {
    val workManager = WorkManager.getInstance(context)
    if (!settings.automaticEnabled) {
        workManager.cancelUniqueWork("periodic_cleanup")
        return
    }
    val interval = if (settings.frequency == Frequency.DAILY) 1L else 7L
    val request = PeriodicWorkRequestBuilder<CleanerWorker>(interval, TimeUnit.DAYS).build()
    workManager.enqueueUniquePeriodicWork("periodic_cleanup", ExistingPeriodicWorkPolicy.UPDATE, request)
}
