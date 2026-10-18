package com.hackpuntes.fridagate.ui.frida

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.ui.extras.ExtrasViewModel
import com.hackpuntes.fridagate.utils.FridaStealthConfig
import com.hackpuntes.fridagate.utils.FridaUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FridaScreen() {
    val context = LocalContext.current
    val viewModel: FridaViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                FridaViewModel(context.applicationContext) as T
        }
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val isInstalled by viewModel.isServerInstalled.collectAsState()
    val isRunning by viewModel.isServerRunning.collectAsState()
    val isServerPortListening by viewModel.isServerPortListening.collectAsState()
    val installedVersion by viewModel.installedVersion.collectAsState()
    val availableReleases by viewModel.availableReleases.collectAsState()
    val selectedVersion by viewModel.selectedVersion.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val lastCustomFlags by viewModel.lastCustomFlags.collectAsState()
    // NUEVO: estado de la config de camuflaje
    val stealthConfig by viewModel.stealthConfig.collectAsState()

    val extrasViewModel: ExtrasViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ExtrasViewModel(context) as T
        }
    )
    val injectInstalled by extrasViewModel.isFridaInjectInstalled.collectAsState()
    val injectVersion by extrasViewModel.fridaInjectVersion.collectAsState()
    val injectLoading by extrasViewModel.isLoading.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, extrasViewModel) {
        viewModel.checkRootAndStatus()
        extrasViewModel.refreshEnvironmentStatus()
    }
    DisposableEffect(lifecycleOwner, viewModel, extrasViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkRootAndStatus()
                extrasViewModel.refreshEnvironmentStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showCustomFlagsDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Section: Device Info ──────────────────────────────────────────
            DeviceInfoCard(isRootAvailable = isRootAvailable)

            // ── Section: Server Status ────────────────────────────────────────
            // CAMBIO: pasa el puerto configurado para mostrarlo en el label
            StatusCard(
                isInstalled = isInstalled,
                isRunning = isRunning,
                isPortListening = isServerPortListening,
                installedVersion = installedVersion,
                activeFlags = lastCustomFlags,
                configuredPort = stealthConfig.port
            )

            // ── NUEVO: Section: Configuración de camuflaje ───────────────────
            StealthSettingsCard(
                currentConfig = stealthConfig,
                onSave = { newConfig ->
                    val error = viewModel.updateStealthConfig(newConfig)
                    if (error != null) {
                        Toast.makeText(context, "⚠️ $error", Toast.LENGTH_LONG).show()
                    }
                    error
                },
                onReset = { viewModel.resetStealthConfig() }
            )

            // ── Section: Version Selector ─────────────────────────────────────
            VersionSelector(
                releases = availableReleases,
                selectedVersion = selectedVersion,
                onVersionSelected = { viewModel.setSelectedVersion(it) },
                onCustomVersion = { viewModel.setCustomVersion(it) },
                enabled = !isLoading && isRootAvailable
            )

            // ── Section: Action Buttons ───────────────────────────────────────
            ActionButtons(
                isInjectInstalled = injectInstalled,
                injectVersion = injectVersion,
                isInjectLoading = injectLoading,
                selectedVersion = selectedVersion,
                onDownloadInject = { version ->
                    extrasViewModel.downloadFridaInject(version) { viewModel.logOperation(it) }
                },
                isInstalled = isInstalled,
                isRunning = isRunning,
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onInstall = { viewModel.downloadAndInstall(context) },
                onStart = { viewModel.startServer() },
                onStartCustom = { showCustomFlagsDialog = true },
                onStop = { viewModel.stopServer() },
                onUninstall = {
                    viewModel.uninstallServer {
                        viewModel.checkRootAndStatus()
                        extrasViewModel.refreshEnvironmentStatus()
                    }
                },
                onRefresh = {
                    viewModel.checkRootAndStatus()
                    viewModel.loadAvailableReleases()
                }
            )

            // ── Section: Log Panel ────────────────────────────────────────────
            LogPanel(
                logs = logs,
                context = context,
                onClear = { viewModel.clearLogs() },
                onExport = { exportFridaLog(context, logs) }
            )
        }

        // ── Loading Overlay ───────────────────────────────────────────────────
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // ── Custom Flags Dialog ───────────────────────────────────────────────────
    if (showCustomFlagsDialog) {
        CustomFlagsDialog(
            initialFlags = lastCustomFlags,
            onConfirm = { flags ->
                viewModel.startServerWithCustomFlags(flags)
                showCustomFlagsDialog = false
            },
            onDismiss = { showCustomFlagsDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceInfoCard(isRootAvailable: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DISPOSITIVO",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${Build.MODEL} · ${FridaUtils.getDeviceArchitecture()}",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!isRootAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ NO HAY ACCESO ROOT: FRIDA-SERVER REQUIERE PERMISOS ROOT",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// CAMBIO: recibe configuredPort para mostrar el puerto correcto en el label
@Composable
private fun StatusCard(
    isInstalled: Boolean,
    isRunning: Boolean,
    isPortListening: Boolean,
    installedVersion: String,
    activeFlags: String,
    configuredPort: Int = 27042
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ESTADO DEL SERVIDOR",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow(label = "Instalado", value = if (isInstalled) "Sí" else "No", isActive = isInstalled)
            StatusRow(label = "En ejecución", value = if (isRunning) "Sí" else "No", isActive = isRunning)
            // CAMBIO: el label usa el puerto configurado, no 27042 hardcodeado
            StatusRow(
                label = "Puerto local $configuredPort",
                value = if (isPortListening) "Escuchando" else "No confirmado",
                isActive = isPortListening
            )
            StatusRow(label = "Versión real", value = installedVersion, isActive = installedVersion != "No instalado")

            if (isRunning) {
                StatusRow(
                    label = "Parámetros",
                    value = if (activeFlags.isBlank()) "predeterminados" else activeFlags,
                    isActive = true
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Box(
            modifier = Modifier.width(132.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionSelector(
    releases: List<com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease>,
    selectedVersion: String,
    onVersionSelected: (String) -> Unit,
    onCustomVersion: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    var customVersion by remember { mutableStateOf("") }
    val versions = releases.map { it.version }.distinct().sortedWith(
        compareByDescending<String> { it.substringBefore('.').toIntOrNull() ?: 0 }
            .thenByDescending { it.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: 0 }
            .thenByDescending { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "VERSIÓN PARA INSTALAR",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedVersion,
                    onValueChange = {},
                    placeholder = { Text("SELECCIONA UNA VERSIÓN DE FRIDA") },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Expandir") },
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                    enabled = enabled,
                    singleLine = true
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    versions.forEach { version ->
                        DropdownMenuItem(
                            text = { Text(version, fontWeight = FontWeight.Normal) },
                            onClick = {
                                onVersionSelected(version)
                                customVersion = ""
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = customVersion,
                onValueChange = { value ->
                    customVersion = value
                    onCustomVersion(value)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("VERSION DE FRIDA...") },
                label = { Text("Versión manual") },
                singleLine = true,
                enabled = enabled
            )
            Text(
                "Versiones disponibles: 17.23.3, 17.6.0 y 16.7.19",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButtons(
    isInjectInstalled: Boolean,
    injectVersion: String?,
    isInjectLoading: Boolean,
    selectedVersion: String,
    onDownloadInject: (String) -> Unit,
    isInstalled: Boolean,
    isRunning: Boolean,
    isLoading: Boolean,
    isRootAvailable: Boolean,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStartCustom: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
    onRefresh: () -> Unit
) {
    var pendingAction by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ENTORNO DE FRIDA",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("ENTORNO DE FRIDA", style = MaterialTheme.typography.titleMedium)
            StatusRow("frida-server", if (isRunning) "● En ejecución" else "● Detenido", isRunning)
            StatusRow(
                "frida-inject",
                if (isInjectInstalled) "● v${injectVersion ?: "?"}" else "● No instalado",
                isInjectInstalled
            )
            Divider()

            Button(
                onClick = { pendingAction = "INSTALAR/ACTUALIZAR SERVER" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable && selectedVersion.isNotBlank()
            ) {
                Text("INSTALAR / ACTUALIZAR SERVER")
            }

            Button(
                onClick = { pendingAction = "INSTALAR/ACTUALIZAR INJECT" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isInjectLoading && !isLoading && isRootAvailable && selectedVersion.isNotBlank()
            ) {
                Text(if (isInjectInstalled) "INSTALAR / ACTUALIZAR INJECT" else "INSTALAR / ACTUALIZAR INJECT")
            }

            if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { pendingAction = "INICIAR" },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && !isRunning && isRootAvailable,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) { Text("INICIAR") }

                    Button(
                        onClick = { pendingAction = "DETENER" },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && isRunning && isRootAvailable,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        )
                    ) { Text("DETENER") }
                }
            }

            OutlinedButton(
                onClick = { pendingAction = "DESINSTALAR" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && !isInjectLoading && isRootAvailable,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("DESINSTALAR") }

            OutlinedButton(
                onClick = { pendingAction = "ACTUALIZAR ESTADO" },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ACTUALIZAR ESTADO")
            }
        }
    }
    pendingAction?.let { action ->
        val description = when (action) {
            "INSTALAR/ACTUALIZAR SERVER" -> "INSTALAR O ACTUALIZAR ÚNICAMENTE FRIDA-SERVER."
            "INSTALAR/ACTUALIZAR INJECT" -> "INSTALAR O ACTUALIZAR FRIDA-INJECT EN LA VERSIÓN SELECCIONADA ($selectedVersion), SIN NECESITAR INSTALAR FRIDA-SERVER PRIMERO."
            "INICIAR" -> "INICIAR EL PROCESO FRIDA-SERVER."
            "DETENER" -> "DETENER EL PROCESO FRIDA-SERVER."
            "ACTUALIZAR ESTADO" -> "VOLVER A COMPROBAR ROOT, VERSIÓN, INSTALACIÓN Y ESTADO DEL SERVIDOR."
            "DESINSTALAR" -> "MATAR LOS PROCESOS FRIDA Y ELIMINAR BINARIOS, ARCHIVOS DE VERSIÓN, REGISTROS DE INYECCIÓN Y PAQUETES SEGUIDOS."
            else -> "CONFIRMAR ACCIÓN."
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(action.uppercase()) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    when (action) {
                        "INSTALAR/ACTUALIZAR SERVER" -> onInstall()
                        "INSTALAR/ACTUALIZAR INJECT" -> onDownloadInject(selectedVersion)
                        "INICIAR" -> onStart()
                        "DETENER" -> onStop()
                        "ACTUALIZAR ESTADO" -> onRefresh()
                        "DESINSTALAR" -> onUninstall()
                    }
                }) { Text("SI") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("NO") } }
        )
    }
}

@Composable
private fun LogPanel(logs: List<String>, context: android.content.Context, onClear: () -> Unit, onExport: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REGISTRO",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("FridaGate 2.0 — Frida", logs.joinToString("\n"))
                        )
                        Toast.makeText(context, "REGISTROS COPIADOS", Toast.LENGTH_SHORT).show()
                    }) { Text("COPIAR") }
                    TextButton(onClick = onExport) { Text("EXPORTAR") }
                    TextButton(onClick = onClear) { Text("LIMPIAR") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(
                        color = Color(0xFF1A1A1A),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "TODAVÍA NO HAY REGISTROS...",
                        color = Color(0xFF666666),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                color = Color(0xFF00FF00),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomFlagsDialog(initialFlags: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var flags by remember { mutableStateOf(initialFlags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("INICIAR CON PARÁMETROS PERSONALIZADOS") },
        text = {
            Column {
                OutlinedTextField(
                    value = flags,
                    onValueChange = { flags = it },
                    label = { Text("INTRODUCE LOS PARÁMETROS AQUÍ") },
                    placeholder = { Text("-L 0.0.0.0:27042") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PARÁMETROS HABITUALES:\n" +
                        "-l ADDRESS  Listen on address\n" +
                        "--token=TOKEN  Require auth token\n" +
                        "-D  Daemonize",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(flags) }) { Text("INICIAR") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR") }
        }
    )
}

private fun exportFridaLog(context: android.content.Context, logs: List<String>) {
    try {
        val directory = File("/sdcard/download")
        if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("No se pudo crear /sdcard/download")
        val stamp = SimpleDateFormat("MM-dd-HH-mm", Locale.US).format(Date())
        val file = File(directory, "frida_log_($stamp).txt")
        file.writeText(logs.joinToString("\n"))
        Toast.makeText(context, "LOG EXPORTADO: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "ERROR AL EXPORTAR LOG: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
