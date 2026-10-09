package com.hackpuntes.fridagate.ui.frida

import android.os.Build
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.hackpuntes.fridagate.ui.extras.ExtrasViewModel
import com.hackpuntes.fridagate.utils.FridaUtils

/**
 * FridaScreen - The Frida tab UI built with Jetpack Compose.
 *
 * In Compose, the UI is a tree of @Composable functions.
 * Each composable describes what the UI looks like for a given state.
 * When the state changes, Compose automatically re-renders only the affected parts.
 *
 * This screen is split into smaller composable functions for readability:
 *  - FridaScreen         → root composable, holds the ViewModel and state
 *  - StatusCard          → shows installed/running status
 *  - VersionSelector     → dropdown to pick a Frida version
 *  - ActionButtons       → install, start, stop, uninstall buttons
 *  - LogPanel            → scrollable terminal-style log
 *  - CustomFlagsDialog   → dialog for entering custom flags
 */
@Composable
fun FridaScreen(
    // The ViewModel is provided by the Compose runtime and survives recompositions
    viewModel: FridaViewModel = viewModel()
) {
    // collectAsState() turns a StateFlow into a Compose State object
    // Every time the StateFlow emits a new value, the composable recomposes
    val isLoading by viewModel.isLoading.collectAsState()
    val isInstalled by viewModel.isServerInstalled.collectAsState()
    val isRunning by viewModel.isServerRunning.collectAsState()
    val installedVersion by viewModel.installedVersion.collectAsState()
    val availableReleases by viewModel.availableReleases.collectAsState()
    val selectedVersion by viewModel.selectedVersion.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val lastCustomFlags by viewModel.lastCustomFlags.collectAsState()

    // Context is needed for operations that require it (e.g., file download)
    val context = LocalContext.current

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

    // State for showing/hiding the custom flags dialog
    var showCustomFlagsDialog by remember { mutableStateOf(false) }

    // Outer Box allows us to overlay the loading indicator on top of everything
    Box(modifier = Modifier.fillMaxSize()) {

        // Main scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Section: Device Info ──────────────────────────────────────────
            // Shows the device model and CPU architecture
            DeviceInfoCard(isRootAvailable = isRootAvailable)

            // ── Section: Server Status ────────────────────────────────────────
            // Shows whether frida-server is installed and running
            StatusCard(
                isInstalled = isInstalled,
                isRunning = isRunning,
                installedVersion = installedVersion,
                activeFlags = lastCustomFlags
            )

            // ── Section: Version Selector ─────────────────────────────────────
            // Dropdown to pick which Frida version to install
            VersionSelector(
                releases = availableReleases,
                selectedVersion = selectedVersion,
                onVersionSelected = { viewModel.setSelectedVersion(it) },
                onCustomVersion = { viewModel.setCustomVersion(it) },
                enabled = !isLoading
            )

            // ── Section: Action Buttons ───────────────────────────────────────
            ActionButtons(
                isInjectInstalled = injectInstalled,
                injectVersion = injectVersion,
                isInjectLoading = injectLoading,
                onDownloadInject = { extrasViewModel.downloadFridaInject() },
                isInstalled = isInstalled,
                isRunning = isRunning,
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onInstall = { viewModel.downloadAndInstall(context) },
                onStart = { viewModel.startServer() },
                onStartCustom = { showCustomFlagsDialog = true },
                onStop = { viewModel.stopServer() },
                onUninstall = { viewModel.uninstallServer() },
                onRefresh = {
                    viewModel.checkRootAndStatus()
                    viewModel.loadAvailableReleases()
                }
            )

            // ── Section: Log Panel ────────────────────────────────────────────
            LogPanel(
                logs = logs,
                onClear = { viewModel.clearLogs() }
            )
        }

        // ── Loading Overlay ───────────────────────────────────────────────────
        // Shown on top of everything when an operation is in progress
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Semi-transparent black background blocks interaction with UI below
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
// Sub-composables — each one is responsible for one section of the screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows device model and CPU architecture, plus a root access warning if needed.
 */
@Composable
private fun DeviceInfoCard(isRootAvailable: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Dispositivo",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Build.MODEL is the device model name (e.g., "Pixel 6")
            // FridaUtils.getDeviceArchitecture() returns "arm64", "arm", etc.
            Text(
                text = "${Build.MODEL} · ${FridaUtils.getDeviceArchitecture()}",
                style = MaterialTheme.typography.bodyMedium
            )

            // Show a warning if root is not available
            if (!isRootAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ No hay acceso root: frida-server requiere permisos root",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Shows the current frida-server status: installed, running, and version.
 * Uses green for active/yes states and red for inactive/no states.
 */
@Composable
private fun StatusCard(
    isInstalled: Boolean,
    isRunning: Boolean,
    installedVersion: String,
    activeFlags: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Estado del servidor",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow(label = "Instalado", value = if (isInstalled) "Sí" else "No", isActive = isInstalled)
            StatusRow(label = "En ejecución", value = if (isRunning) "Sí" else "No", isActive = isRunning)
            StatusRow(label = "Versión", value = installedVersion, isActive = installedVersion != "No instalado")

            // Show active flags only when the server is running
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

/**
 * A single row showing a label and a colored status value.
 *
 * @param label   The left-side label (e.g., "Installed")
 * @param value   The right-side value (e.g., "Yes")
 * @param isActive Whether to use green (true) or red (false) for the value
 */
@Composable
private fun StatusRow(label: String, value: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            // Green when active, red when inactive
            color = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    }
}

/**
 * Dropdown menu for selecting a Frida version to install.
 *
 * ExposedDropdownMenuBox is the Material3 Compose equivalent of a Spinner.
 * It shows the selected item and expands to show all options when tapped.
 */
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
    val latestVersion = releases.firstOrNull()?.version
    val versions = buildList {
        add("16.7.19" to "Recomendada")
        latestVersion?.let { add(it to "Última") }
    }.distinctBy { it.first }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Versión para instalar", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = when (selectedVersion) {
                        "16.7.19" -> "16.7.19 (Recomendada)"
                        latestVersion -> if (latestVersion != null) "$latestVersion (Latest)" else "16.7.19 (Recommended)"
                        else -> selectedVersion.ifEmpty { "Seleccionar versión..." }
                    },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Expandir") },
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                    enabled = enabled
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    versions.forEach { (version, label) ->
                        DropdownMenuItem(
                            text = { Text("$version ($label)", fontWeight = FontWeight.Bold) },
                            onClick = {
                                onVersionSelected(version)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * All action buttons for frida-server management.
 * Buttons are enabled/disabled based on the current state.
 */
@Composable
private fun ActionButtons(
    isInjectInstalled: Boolean,
    injectVersion: String?,
    isInjectLoading: Boolean,
    onDownloadInject: () -> Unit,
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Frida Environments",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Entorno de Frida", style = MaterialTheme.typography.titleMedium)
            StatusRow("frida-server", if (isRunning) "● En ejecución" else "● Detenido", isRunning)
            StatusRow("frida-inject", if (isInjectInstalled) "● v${injectVersion ?: "?"}" else "● No instalado", isInjectInstalled)
            if (!isInjectInstalled) {
                Text("Necesario para lanzar scripts desde el dispositivo. Se instala con la misma versión que frida-server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onDownloadInject, enabled = !isInjectLoading && !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("Descargar frida-inject")
                }
            }
            Divider()

            // Install button — always visible, disabled when root is unavailable or loading
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable
            ) {
                Text("Instalar / actualizar Frida Server")
            }

            // Start/Stop/Custom buttons — only shown when frida-server is installed
            if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start button — disabled when already running
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50) // Green
                        )
                    ) { Text("Iniciar") }

                    // Stop button — disabled when not running
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336) // Red
                        )
                    ) { Text("Detener") }
                }

                // Custom flags button — disabled when already running
                OutlinedButton(
                    onClick = onStartCustom,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isRunning
                ) { Text("Iniciar con parámetros personalizados") }

                // Uninstall button
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Desinstalar") }
            }

            // Refresh button — always available
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Actualizar estado")
            }
        }
    }
}

/**
 * Terminal-style scrollable log panel.
 *
 * LazyColumn is Compose's equivalent of RecyclerView — it only renders
 * the items that are currently visible on screen, making it memory-efficient
 * for long lists.
 */
@Composable
private fun LogPanel(
    logs: List<String>,
    onClear: () -> Unit
) {
    // Used to programmatically scroll to the bottom when new logs arrive
    val listState = rememberLazyListState()

    // Auto-scroll to bottom whenever the logs list changes
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // animateScrollToItem scrolls smoothly to the last item
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
                    text = "Registro",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onClear) { Text("Limpiar") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dark background to simulate a terminal
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
                        text = "Todavía no hay registros...",
                        color = Color(0xFF666666),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                color = Color(0xFF00FF00), // Green text like a terminal
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

/**
 * Dialog for entering custom frida-server flags.
 *
 * @param initialFlags The last used flags (pre-filled for convenience)
 * @param onConfirm Called with the entered flags when the user taps Start
 * @param onDismiss Called when the user cancels
 */
@Composable
private fun CustomFlagsDialog(
    initialFlags: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // remember keeps this value across recompositions within this dialog's lifetime
    var flags by remember { mutableStateOf(initialFlags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Iniciar con parámetros personalizados") },
        text = {
            Column {
                OutlinedTextField(
                    value = flags,
                    onValueChange = { flags = it },
                    label = { Text("Introduce los parámetros aquí") },
                    placeholder = { Text("-l 0.0.0.0:27042") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Help text showing common flags
                Text(
                    text = "Common flags:\n" +
                            "-l ADDRESS  Listen on address\n" +
                            "--token=TOKEN  Require auth token\n" +
                            "-D  Daemonize",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(flags) }) { Text("Iniciar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
