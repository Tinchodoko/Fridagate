package com.hackpuntes.fridagate.ui.dashboard

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.FridaStealthConfig
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardViewModel - Manages the global status overview and one-tap actions.
 *
 * This ViewModel reports Android permissions and manages Frida actions.
 *
 * It also provides "ACTIVATE ALL" and "DEACTIVATE ALL" — convenience actions
 * that run the full setup or teardown sequence in a single coroutine.
 *
 * We define it here (in the same file as the screen) because it is only
 * used by DashboardScreen. When a ViewModel grows large, move it to its own file.
 */
class DashboardViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    // ── Status flags ──────────────────────────────────────────────────────────

    /** Whether the device has root access */
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /** Whether frida-server is installed on the device */
    private val _isFridaInstalled = MutableStateFlow(false)
    val isFridaInstalled: StateFlow<Boolean> = _isFridaInstalled.asStateFlow()

    /** Whether frida-server process is running */
    private val _isFridaRunning = MutableStateFlow(false)
    val isFridaRunning: StateFlow<Boolean> = _isFridaRunning.asStateFlow()

    /** Whether frida-inject is installed in Original or Stealth mode */
    private val _isInjectInstalled = MutableStateFlow(false)
    val isInjectInstalled: StateFlow<Boolean> = _isInjectInstalled.asStateFlow()

    private var detectedConfig: FridaStealthConfig = FridaStealthConfig.DEFAULT

    /** Whether a background operation is in progress */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Combined log from all operations performed via the dashboard */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        // Refresh all status values when the dashboard opens
        refreshStatus()
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    /**
     * Checks the current state of all components and updates the status flags.
     * Called on init and when the user taps the Refresh button.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                addLog("Actualizando estado de Frida...")
                _isRootAvailable.value = RootUtils.isRootAvailable()
                val savedConfig = AppPreferences(appContext).stealthConfig.first()
                val candidates = listOf(savedConfig, FridaStealthConfig.DEFAULT, FridaStealthConfig.STEALTH).distinct()
                var foundServerConfig: FridaStealthConfig? = null
                var foundInjectConfig: FridaStealthConfig? = null
                var anyServerInstalled = false
                var anyInjectInstalled = false
                var anyServerRunning = false

                for (config in candidates) {
                    FridaUtils.configure(config)
                    val serverInstalled = FridaUtils.isFridaServerInstalled()
                    val injectInstalled = FridaInjectUtils.isFridaInjectInstalled()
                    val serverRunning = serverInstalled && FridaUtils.isFridaServerRunning()
                    if (serverInstalled) {
                        anyServerInstalled = true
                        if (foundServerConfig == null) foundServerConfig = config
                    }
                    if (injectInstalled) {
                        anyInjectInstalled = true
                        if (foundInjectConfig == null) foundInjectConfig = config
                    }
                    if (serverRunning) {
                        anyServerRunning = true
                        foundServerConfig = config
                    }
                }
                detectedConfig = foundServerConfig ?: foundInjectConfig ?: savedConfig
                FridaUtils.configure(detectedConfig)
                _isFridaInstalled.value = anyServerInstalled
                _isInjectInstalled.value = anyInjectInstalled
                _isFridaRunning.value = anyServerRunning
                addLog("Estado: server=" + (if (anyServerInstalled) "instalado" else "no instalado") +
                    ", inject=" + (if (anyInjectInstalled) "instalado" else "no instalado") +
                    ", ejecución=" + (if (anyServerRunning) "activa" else "detenida") + ".")
            } catch (e: Exception) {
                addLog("ERROR al actualizar el estado: " + (e.message ?: "desconocido"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── One-tap actions ───────────────────────────────────────────────────────

    /**
     * Starts frida-server. Uses a coroutine and logs each step for the user.
     */
    fun activateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                addLog("── ACTIVAR FRIDA ──────────────────")
                if (!_isFridaInstalled.value) {
                    addLog("ERROR: FRIDA-SERVER NO ESTÁ INSTALADO. USA INSTALACIÓN INTELIGENTE.")
                    return@launch
                }
                FridaUtils.configure(detectedConfig)
                val started = if (FridaUtils.isFridaServerRunning()) true else FridaUtils.startFridaServer()
                _isFridaRunning.value = started
                addLog(if (started) "FRIDA-SERVER ESTÁ EN EJECUCIÓN." else "ERROR: NO SE PUDO INICIAR FRIDA-SERVER")
            } catch (e: Exception) {
                addLog("ERROR AL ACTIVAR FRIDA: ${e.message ?: "DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Stops injected scripts and frida-server while preserving installed binaries. */

    /**
     * Instala y prepara en secuencia los componentes principales de FridaGate.
     * Requiere root y conexión a Internet. Usa la misma versión para server e inject.
     */
    fun smartInstallAll(version: String, config: FridaStealthConfig) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                addLog("── INSTALACIÓN INTELIGENTE ─────────")
                if (!RootUtils.isRootAvailable()) {
                    _isRootAvailable.value = false
                    addLog("ERROR: SE NECESITA ACCESO ROOT PARA INSTALAR FRIDA")
                    return@launch
                }
                _isRootAvailable.value = true

                if (!FridaUtils.isValidVersionFormat(version)) {
                    addLog("ERROR: LA VERSIÓN INDICADA NO TIENE UN FORMATO VÁLIDO (EJEMPLO: 16.5.5).")
                    return@launch
                }
                config.validate()?.let {
                    addLog("ERROR EN CONFIGURACIÓN DE CAMUFLAJE: $it")
                    return@launch
                }
                AppPreferences(appContext).saveStealthConfig(config)
                FridaUtils.configure(config)
                appContext.getSharedPreferences("fridagate_settings", Context.MODE_PRIVATE)
                    .edit().putString("selected_frida_version", version).apply()
                val architecture = FridaUtils.getDeviceArchitecture()
                addLog("VERSIÓN SELECCIONADA: $version ($architecture)")
                addLog("MODO: " + if (config == FridaStealthConfig.DEFAULT) "ORIGINAL" else "STEALTH")
                addLog("DIRECTORIO: ${config.installDir} | SERVER: ${config.serverAlias} | INJECT: ${config.injectAlias} | PUERTO: ${config.port}")
                addLog("Verificando que GitHub publique server e inject para la misma versión...")
                val serverAssetAvailable = FridaUtils.isFridaAssetAvailable(version, architecture, "server")
                val injectAssetAvailable = FridaUtils.isFridaAssetAvailable(version, architecture, "inject")
                if (!serverAssetAvailable || !injectAssetAvailable) {
                    addLog("ERROR: NO SE INSTALÓ NINGÚN BINARIO. La versión $version no ofrece ambos archivos Android para $architecture o GitHub no respondió.")
                    addLog("Elige otra versión o verifica la conexión; server e inject deben tener la misma versión.")
                    return@launch
                }

                if (!FridaUtils.isFridaServerInstalled() ||
                    FridaUtils.getInstalledFridaVersion() != version
                ) {
                    addLog("Descargando e instalando frida-server $version...")
                    val url = FridaUtils.getFridaServerUrl(version, architecture)
                    if (url == null) {
                        addLog("ERROR: No se encontró la descarga de frida-server $version ($architecture)")
                        return@launch
                    }
                    val file = FridaUtils.downloadFridaServerFromUrl(appContext, url)
                    if (file == null) {
                        addLog("ERROR: No se pudo descargar frida-server")
                        return@launch
                    }
                    val installed = FridaUtils.installFridaServer(file, version)
                    file.delete()
                    if (!installed) {
                        addLog("ERROR: Falló la instalación de frida-server")
                        return@launch
                    }
                    addLog("frida-server $version instalado")
                } else {
                    addLog("frida-server $version ya está instalado")
                }
                _isFridaInstalled.value = FridaUtils.isFridaServerInstalled()

                if (!FridaInjectUtils.isFridaInjectInstalled() ||
                    FridaInjectUtils.getInstalledVersion() != version
                ) {
                    addLog("Descargando e instalando frida-inject $version...")
                    if (!FridaInjectUtils.downloadAndInstall(appContext, version)) {
                        addLog("ERROR: Falló la instalación de frida-inject")
                        return@launch
                    }
                    addLog("frida-inject $version instalado")
                } else {
                    addLog("frida-inject $version ya está instalado")
                }

                addLog("Iniciando frida-server...")
                _isFridaRunning.value = FridaUtils.startFridaServer()
                if (!_isFridaRunning.value) {
                    addLog("ERROR: No se pudo iniciar frida-server")
                    return@launch
                }
                _isFridaInstalled.value = FridaUtils.isFridaServerInstalled()
                _isInjectInstalled.value = FridaInjectUtils.isFridaInjectInstalled()
                _isFridaRunning.value = FridaUtils.isFridaServerRunning()
                detectedConfig = config
                addLog("frida-server iniciado")
                addLog("Frida-server está instalado y en ejecución.")
                addLog("frida-inject está instalado con la misma versión.")
                addLog("── INSTALACIÓN INTELIGENTE FINALIZADA ──")
            } catch (e: Exception) {
                addLog("ERROR en instalación inteligente: ${e.message ?: "error desconocido"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deactivateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("── DETENER TODO ──────────────────")
            try {
                addLog("Deteniendo scripts inyectados y aplicaciones registradas...")
                FridaInjectUtils.stopAllInjections().forEach { addLog(it) }

                addLog("Deteniendo frida-server...")
                val stopped = FridaUtils.stopFridaServer()
                _isFridaRunning.value = FridaUtils.isFridaServerRunning()
                addLog(
                    if (stopped &&
                        !_isFridaRunning.value
                    ) {
                        "✓ frida-server detenido"
                    } else {
                        "ADVERTENCIA: no se pudo confirmar que frida-server se detuviera"
                    }
                )

                _isFridaRunning.value = false
                addLog("Detener todo finalizado. Los binarios de Frida se conservan instalados.")
            } catch (e: Exception) {
                addLog("ERROR al detener todo: ${e.message ?: "desconocido"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Stops and removes Frida server + inject in both Original and Stealth locations. */
    fun uninstallAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                addLog("── DESINSTALAR TODO ───────────────")
                val savedConfig = AppPreferences(appContext).stealthConfig.first()
                val candidates = listOf(savedConfig, FridaStealthConfig.DEFAULT, FridaStealthConfig.STEALTH).distinct()
                addLog("Deteniendo inyecciones activas...")
                FridaInjectUtils.stopAllInjections().forEach { addLog(it) }

                for (config in candidates) {
                    FridaUtils.configure(config)
                    val serverInstalled = FridaUtils.isFridaServerInstalled()
                    val injectInstalled = FridaInjectUtils.isFridaInjectInstalled()
                    val serverRunning = serverInstalled && FridaUtils.isFridaServerRunning()
                    if (serverRunning) {
                        addLog("Deteniendo " + config.serverAlias + " antes de desinstalar...")
                        val stopped = FridaUtils.stopFridaServer()
                        if (!stopped || FridaUtils.isFridaServerRunning()) {
                            addLog("ERROR: no se pudo confirmar la detención de " + config.serverAlias + "; se omite esa configuración.")
                            continue
                        }
                    }
                    if (injectInstalled) {
                        val removedInject = FridaInjectUtils.uninstallFridaInject()
                        addLog(if (removedInject) "frida-inject eliminado de " + config.injectBinaryPath else "ERROR al eliminar frida-inject de " + config.injectBinaryPath)
                    }
                    if (serverInstalled) {
                        val removedServer = FridaUtils.uninstallFridaServer()
                        addLog(if (removedServer) "frida-server eliminado de " + config.serverBinaryPath else "ERROR al eliminar frida-server de " + config.serverBinaryPath)
                    }
                }
                val installDirs = linkedSetOf(
                    savedConfig.installDir,
                    FridaStealthConfig.DEFAULT.installDir,
                    FridaStealthConfig.STEALTH.installDir,
                    "/data/local/tmp/frida"
                ).filter { it.startsWith("/data/local/tmp/") && it != "/data/local/tmp" }
                for (dir in installDirs) {
                    val quotedDir = "'" + dir.replace("'", "'\\''") + "'"
                    val cleanup = RootUtils.executeSuCommand(
                        "if [ -d $quotedDir ]; then if rmdir $quotedDir 2>/dev/null; then echo 'DIRECTORIO ELIMINADO: ' $quotedDir; else echo 'DIRECTORIO NO VACÍO, SE CONSERVA: ' $quotedDir; fi; fi"
                    )
                    if (cleanup.isNotBlank()) addLog(cleanup)
                }
                FridaUtils.configure(savedConfig)
                addLog("── DESINSTALACIÓN FINALIZADA ──────")
            } catch (e: Exception) {
                addLog("ERROR durante la desinstalación: " + (e.message ?: "desconocido"))
            } finally {
                _isLoading.value = false
                refreshStatus()
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardScreen - Global status overview and one-tap interception control.
 */
@Composable
fun DashboardScreen() {
    val context = LocalContext.current

    val viewModel: DashboardViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardViewModel(context) as T
        }
    )

    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val isFridaInstalled by viewModel.isFridaInstalled.collectAsState()
    val isFridaRunning by viewModel.isFridaRunning.collectAsState()
    val isInjectInstalled by viewModel.isInjectInstalled.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val logs by viewModel.logs.collectAsState()
    var pendingConfirmation by remember { mutableStateOf<String?>(null) }
    var showSmartInstallWizard by remember { mutableStateOf(false) }

    var hasStoragePermission by remember { mutableStateOf(storagePermissionGranted(context)) }
    var hasNotificationPermission by remember { mutableStateOf(notificationPermissionGranted(context)) }
    var hasOverlayPermission by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var hasBackgroundPermission by remember { mutableStateOf(backgroundExecutionAllowed(context)) }
    var isFloatingIconVisible by remember { mutableStateOf(floatingIconIsVisible(context)) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(batteryOptimizationIgnored(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotificationPermission = notificationPermissionGranted(context)
    }
    val requestLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasStoragePermission = storagePermissionGranted(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = storagePermissionGranted(context)
                hasNotificationPermission = notificationPermissionGranted(context)
                hasOverlayPermission = android.provider.Settings.canDrawOverlays(context)
                hasBackgroundPermission = backgroundExecutionAllowed(context)
                isFloatingIconVisible = floatingIconIsVisible(context)
                isIgnoringBatteryOptimizations = batteryOptimizationIgnored(context)
                viewModel.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Status overview card ──────────────────────────────────────────
            PermissionControlsCard(
                storageGranted = hasStoragePermission,
                notificationsGranted = hasNotificationPermission,
                overlayGranted = hasOverlayPermission,
                rootGranted = isRootAvailable,
                backgroundGranted = hasBackgroundPermission,
                batteryExempt = isIgnoringBatteryOptimizations,
                onRequestStorage = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        requestLegacyStorage.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                },
                onRequestOverlay = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                },
                onRequestRoot = { viewModel.refreshStatus() },
                onRequestBackground = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName)
                        )
                    )
                },
                onRequestBattery = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
            )

            // ── Status overview card ──────────────────────────────────────────
            StatusOverviewCard(
                isRootAvailable = isRootAvailable,
                isFridaInstalled = isFridaInstalled,
                isFridaRunning = isFridaRunning,
                hasStoragePermission = hasStoragePermission,
                hasNotificationPermission = hasNotificationPermission,
                hasOverlayPermission = hasOverlayPermission,
                hasBackgroundPermission = hasBackgroundPermission,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations
            )

            // ── One-tap action buttons ────────────────────────────────────────
            OneTabActionsCard(
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                isFridaInstalled = isFridaInstalled,
                isInjectInstalled = isInjectInstalled,
                isFridaRunning = isFridaRunning,
                onActivateAll = { pendingConfirmation = "ACTIVAR TODO" },
                onSmartInstall = {
                    if (isFridaInstalled && isInjectInstalled) pendingConfirmation = "DESINSTALAR TODO"
                    else showSmartInstallWizard = true
                },
                onDeactivateAll = { pendingConfirmation = "DETENER TODO" },
                onRefresh = { pendingConfirmation = "ACTUALIZAR ESTADO" },
                isFloatingIconVisible = isFloatingIconVisible,
                canShowFloatingIcon = hasOverlayPermission,
                onToggleFloatingIcon = {
                    pendingConfirmation =
                        if (isFloatingIconVisible) "OCULTAR ICONO FLOTANTE" else "MOSTRAR ICONO FLOTANTE"
                }
            )

            // ── Log panel ─────────────────────────────────────────────────────
            DashboardLogPanel(
                logs = logs,
                context = context,
                onClear = { viewModel.clearLogs() },
                onExport = { exportLogFile(context, "inicio_log", logs) }
            )
        }

        pendingConfirmation?.let { action ->
            val description = when (action) {
                "ACTIVAR TODO" -> "INICIAR FRIDA-SERVER."
                "DETENER TODO" -> "DETENER INYECCIONES Y FRIDA-SERVER."
                "DESINSTALAR TODO" -> if (isFridaRunning) "FRIDA ESTÁ EN EJECUCIÓN. AL CONFIRMAR, SE DETENDRÁN LAS INYECCIONES, SE DETENDRÁ FRIDA-SERVER Y SE DESINSTALARÁN SERVER E INJECT EN ORIGINAL Y STEALTH." else "AL CONFIRMAR, SE DESINSTALARÁN FRIDA-SERVER Y FRIDA-INJECT EN ORIGINAL Y STEALTH."
                "ACTUALIZAR ESTADO" -> "VOLVER A COMPROBAR ROOT, FRIDA Y EL ESTADO DEL SISTEMA."
                "MOSTRAR ICONO FLOTANTE" -> "MOSTRAR EL ICONO FLOTANTE DE FRIDAGATE 2.0 SOBRE OTRAS APLICACIONES."
                else -> "OCULTAR EL ICONO FLOTANTE DE FRIDAGATE 2.0."
            }
            AlertDialog(
                onDismissRequest = { pendingConfirmation = null },
                title = { Text(action.uppercase()) },
                text = { Text(description.uppercase()) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmation = null
                        when (action) {
                            "ACTIVAR TODO" -> viewModel.activateAll()

                            "DETENER TODO" -> viewModel.deactivateAll()

                            "DESINSTALAR TODO" -> viewModel.uninstallAll()

                            "ACTUALIZAR ESTADO" -> viewModel.refreshStatus()

                            "MOSTRAR ICONO FLOTANTE", "OCULTAR ICONO FLOTANTE" -> {
                                if (isFloatingIconVisible) {
                                    context.startService(
                                        Intent(
                                            context,
                                            com.hackpuntes.fridagate.FridaGateNotificationService::class.java
                                        ).apply {
                                            this.action =
                                                com.hackpuntes.fridagate.FridaGateNotificationService.ACTION_HIDE
                                        }
                                    )
                                    isFloatingIconVisible = false
                                } else if (hasOverlayPermission) {
                                    ContextCompat.startForegroundService(
                                        context,
                                        Intent(
                                            context,
                                            com.hackpuntes.fridagate.FridaGateNotificationService::class.java
                                        )
                                    )
                                    isFloatingIconVisible = true
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + context.packageName)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }) { Text("SI") }
                },
                dismissButton = { TextButton(onClick = { pendingConfirmation = null }) { Text("NO") } }
            )
        }

        if (showSmartInstallWizard) {
            SmartInstallWizardDialog(
                onDismiss = { showSmartInstallWizard = false },
                onInstall = { version, config ->
                    showSmartInstallWizard = false
                    viewModel.smartInstallAll(version, config)
                }
            )
        }

        // Loading overlay
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
}

private fun storagePermissionGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            )
}

private fun floatingIconIsVisible(context: Context): Boolean =
    context.getSharedPreferences("fridagate_runtime", Context.MODE_PRIVATE)
        .getBoolean("floating_icon_visible", false)

private fun notificationPermissionGranted(context: Context): Boolean = (
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) &&
    (
        context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as android.app.NotificationManager
        ).areNotificationsEnabled()

private fun backgroundExecutionAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
    !(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

private fun batteryOptimizationIgnored(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
    (
        context.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager
        ).isIgnoringBatteryOptimizations(context.packageName)

@Composable
private fun PermissionControlsCard(
    storageGranted: Boolean,
    notificationsGranted: Boolean,
    overlayGranted: Boolean,
    rootGranted: Boolean,
    backgroundGranted: Boolean,
    batteryExempt: Boolean,
    onRequestStorage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestRoot: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestBattery: () -> Unit
) {
    val missingAny =
        !storageGranted || !notificationsGranted || !overlayGranted || !rootGranted || !backgroundGranted ||
            !batteryExempt
    if (!missingAny) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Permisos y ejecución",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Las opciones desaparecen automáticamente cuando el sistema confirma el permiso.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!storageGranted) {
                OutlinedButton(onClick = onRequestStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("PERMITIR ACCESO AL ALMACENAMIENTO")
                }
            }
            if (!notificationsGranted) {
                OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text("PERMITIR NOTIFICACIONES")
                }
            }
            if (!overlayGranted) {
                OutlinedButton(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
                    Text("PERMITIR ICONO FLOTANTE (MOSTRAR SOBRE OTRAS APPS)")
                }
            }
            if (!rootGranted) {
                OutlinedButton(onClick = onRequestRoot, modifier = Modifier.fillMaxWidth()) {
                    Text("PERMITIR ACCESO ROOT")
                }
            }
            if (!backgroundGranted) {
                OutlinedButton(onClick = onRequestBackground, modifier = Modifier.fillMaxWidth()) {
                    Text("PERMITIR EJECUCIÓN EN SEGUNDO PLANO")
                }
            }
            if (!batteryExempt) {
                OutlinedButton(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
                    Text("DESACTIVAR OPTIMIZACIÓN DE BATERÍA")
                }
            }
        }
    }
}

/**
 * Card showing all component statuses as a grid of indicator rows.
 * Each row shows a colored dot + label + value.
 */
@Composable
private fun StatusOverviewCard(
    isRootAvailable: Boolean,
    isFridaInstalled: Boolean,
    isFridaRunning: Boolean,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    hasOverlayPermission: Boolean,
    hasBackgroundPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "ESTADO DEL SISTEMA",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider()

            // Each StatusIndicatorRow shows one component's status
            StatusIndicatorRow(
                label = "Frida Installed",
                active = isFridaInstalled,
                activeText = "Sí",
                inactiveText = "No"
            )
            StatusIndicatorRow(
                label = "Frida Running",
                active = isFridaRunning,
                activeText = "En ejecución",
                inactiveText = "Detenido"
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            StatusIndicatorRow(
                label = "Permiso de almacenamiento",
                active = hasStoragePermission,
                activeText = "Concedido",
                inactiveText = "Pendiente"
            )
            StatusIndicatorRow(
                label = "Permiso de notificaciones",
                active = hasNotificationPermission,
                activeText = "Concedido",
                inactiveText = "Pendiente"
            )
            StatusIndicatorRow(
                label = "Ventana flotante",
                active = hasOverlayPermission,
                activeText = "Concedido",
                inactiveText = "Pendiente"
            )
            StatusIndicatorRow(
                label = "Acceso root",
                active = isRootAvailable,
                activeText = "Concedido",
                inactiveText = "No disponible"
            )
            StatusIndicatorRow(
                label = "Restricciones en segundo plano",
                active = !hasBackgroundPermission,
                activeText = "Sí",
                inactiveText = "No",
                activeColor = Color(0xFFF44336),
                inactiveColor = Color(0xFF4CAF50)
            )
            StatusIndicatorRow(
                label = "Optimización de batería",
                active = isIgnoringBatteryOptimizations,
                activeText = "Desactivada",
                inactiveText = "Activa"
            )
        }
    }
}

/**
 * A single status row with a colored indicator dot.
 *
 * @param label       Name of the component (e.g., "Frida Running")
 * @param active      Whether the component is in the active/good state
 * @param activeText  Text to show when active (e.g., "Running")
 * @param inactiveText Text to show when inactive (e.g., "Stopped")
 */
@Composable
private fun StatusIndicatorRow(
    label: String,
    active: Boolean,
    activeText: String,
    inactiveText: String,
    activeColor: Color = Color(0xFF4CAF50),
    inactiveColor: Color = Color(0xFFF44336)
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier.width(132.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed-width status column keeps every colored dot aligned in one vertical line.
            Text(
                text = "●",
                color = if (active) activeColor else inactiveColor,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (active) activeText else inactiveText,
                fontWeight = FontWeight.Bold,
                color = if (active) activeColor else inactiveColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Card with ACTIVATE ALL, DEACTIVATE ALL, and Refresh buttons.
 */
@Composable
private fun OneTabActionsCard(
    isLoading: Boolean,
    isRootAvailable: Boolean,
    isFridaInstalled: Boolean,
    isInjectInstalled: Boolean,
    isFridaRunning: Boolean,
    onActivateAll: () -> Unit,
    onSmartInstall: () -> Unit,
    onDeactivateAll: () -> Unit,
    onRefresh: () -> Unit,
    isFloatingIconVisible: Boolean,
    canShowFloatingIcon: Boolean,
    onToggleFloatingIcon: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ACCIONES RÁPIDAS",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            // ACTIVATE ALL — green, prominent
            Button(
                onClick = onActivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable && isFridaInstalled && !isFridaRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = "▶  ACTIVAR TODO",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // SMART INSTALL — installs Frida server + inject
            Button(
                onClick = onSmartInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = if (isFridaInstalled && isInjectInstalled) "⚙  DESINSTALAR TODO" else "⚙  INSTALACIÓN INTELIGENTE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // DEACTIVATE ALL — red
            Button(
                onClick = onDeactivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable && isFridaRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = "■  DETENER TODO",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Refresh status
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text("ACTUALIZAR ESTADO")
            }

            OutlinedButton(
                onClick = onToggleFloatingIcon,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && (canShowFloatingIcon || isFloatingIconVisible)
            ) {
                Text(if (isFloatingIconVisible) "OCULTAR ICONO FLOTANTE" else "MOSTRAR ICONO FLOTANTE")
            }
        }
    }
}

/**
 * Log panel for the dashboard — same pattern as the other screens.
 */
@Composable
private fun DashboardLogPanel(logs: List<String>, context: Context, onClear: () -> Unit, onExport: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("REGISTRO", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("FridaGate 2.0 — Inicio", logs.joinToString("\n"))
                        )
                        android.widget.Toast.makeText(
                            context,
                            "REGISTROS COPIADOS",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("COPIAR") }
                    TextButton(onClick = onExport) { Text("EXPORTAR") }
                    TextButton(onClick = onClear) { Text("LIMPIAR") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "Todavía no hay registros...",
                        color = Color(0xFF666666),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                // White/grey for dashboard logs — neutral
                                color = Color(0xFFCCCCCC),
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

private fun exportLogFile(context: Context, prefix: String, logs: List<String>) {
    try {
        val directory = java.io.File("/sdcard/download")
        if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("No se pudo crear /sdcard/download")
        val stamp = java.text.SimpleDateFormat("MM-dd-HH-mm", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(directory, "${prefix}_($stamp).txt")
        file.writeText(logs.joinToString("\n"))
        android.widget.Toast.makeText(
            context,
            "LOG EXPORTADO: ${file.absolutePath}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "ERROR AL EXPORTAR LOG: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartInstallWizardDialog(
    onDismiss: () -> Unit,
    onInstall: (String, FridaStealthConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    var releases by remember { mutableStateOf<List<FridaUtils.FridaRelease>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("17.24.0") }
    var manualVersion by remember { mutableStateOf("") }
    var versionChoiceMade by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("ORIGINAL") }
    var installDir by remember { mutableStateOf(FridaStealthConfig.STEALTH.installDir) }
    var serverAlias by remember { mutableStateOf(FridaStealthConfig.STEALTH.serverAlias) }
    var injectAlias by remember { mutableStateOf(FridaStealthConfig.STEALTH.injectAlias) }
    var portText by remember { mutableStateOf(FridaStealthConfig.STEALTH.port.toString()) }

    LaunchedEffect(Unit) {
        releases = FridaUtils.getAvailableFridaReleases()
        if (!versionChoiceMade) {
            selectedVersion = releases.firstOrNull()?.version ?: "17.24.0"
        }
    }

    val latestVersion = releases.firstOrNull()?.version ?: "17.24.0"
    val featured = listOf(latestVersion, "17.6.0", "16.7.19").distinct()
    val chosenVersion = manualVersion.trim().ifBlank { selectedVersion }
    val validVersion = FridaUtils.isValidVersionFormat(chosenVersion)
    val config = if (mode == "ORIGINAL") {
        FridaStealthConfig.DEFAULT
    } else {
        FridaStealthConfig(
            installDir = installDir.trim(),
            serverAlias = serverAlias.trim(),
            injectAlias = injectAlias.trim(),
            port = portText.toIntOrNull() ?: -1
        )
    }
    val validationError = config.validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (step == 1) "SELECCIONE UNA VERSIÓN DE FRIDA" else "CONFIGURACIÓN DE STEALTH",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (step == 1) {
                    Text(
                        "Elegí una versión para instalar frida-server y frida-inject juntos.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    featured.forEachIndexed { index, version ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = manualVersion.isBlank() && selectedVersion == version,
                                onClick = {
                                    selectedVersion = version
                                    manualVersion = ""
                                    versionChoiceMade = true
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("V - $version", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when (index) {
                                        0 -> "ÚLTIMA VERSIÓN PUBLICADA"
                                        1 -> "RECOMENDADA"
                                        else -> "PRIMERAS PRUEBAS"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manualVersion,
                        onValueChange = {
                            manualVersion = it.trim()
                            versionChoiceMade = true
                        },
                        label = { Text("BUSCAR UNA VERSIÓN MANUALMENTE") },
                        placeholder = { Text("Ejemplo: 16.5.5") },
                        supportingText = { Text("Se comprobará que GitHub tenga los binarios server e inject para tu arquitectura.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (manualVersion.isNotBlank() && !validVersion) {
                        Text("Formato inválido. Usa el formato 16.5.5.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "CONFIGURACIÓN DE INSTALACIÓN",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("ORIGINAL", fontWeight = FontWeight.Bold)
                            Text("Directorio: ${FridaStealthConfig.DEFAULT.installDir}")
                            Text("Server: ${FridaStealthConfig.DEFAULT.serverAlias}")
                            Text("Inject: ${FridaStealthConfig.DEFAULT.injectAlias}")
                            Text("Puerto: ${FridaStealthConfig.DEFAULT.port}")
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("STEALTH", fontWeight = FontWeight.Bold)
                            Text("Directorio: $installDir")
                            Text("Server: $serverAlias")
                            Text("Inject: $injectAlias")
                            Text("Puerto: $portText")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == "ORIGINAL",
                            onClick = { mode = "ORIGINAL" }
                        )
                        Text("ORIGINAL")
                        Spacer(Modifier.width(12.dp))
                        RadioButton(
                            selected = mode == "STEALTH",
                            onClick = {
                                if (mode != "STEALTH") {
                                    mode = "STEALTH"
                                    installDir = FridaStealthConfig.STEALTH.installDir
                                    serverAlias = FridaStealthConfig.STEALTH.serverAlias
                                    injectAlias = FridaStealthConfig.STEALTH.injectAlias
                                    portText = FridaStealthConfig.STEALTH.port.toString()
                                }
                            }
                        )
                        Text("STEALTH")
                    }
                    if (mode == "STEALTH") {
                        OutlinedTextField(
                            value = installDir,
                            onValueChange = { installDir = it },
                            label = { Text("Directorio de instalación") },
                            placeholder = { Text("/data/local/tmp/.sys") },
                            supportingText = { Text("No usar /sdcard: Android no permite ejecutar binarios desde allí.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = serverAlias,
                            onValueChange = { serverAlias = it },
                            label = { Text("Nombre del proceso server") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = injectAlias,
                            onValueChange = { injectAlias = it },
                            label = { Text("Nombre del proceso inject") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it },
                            label = { Text("Puerto de escucha") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (validationError != null) {
                            Text(validationError, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text(
                            "Se utilizarán las rutas y nombres originales de Frida.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Versión seleccionada: $chosenVersion",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step == 1) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("✕ CANCELAR") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = validVersion,
                        onClick = { step = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("SIGUIENTE ✓") }
                } else {
                    Button(
                        onClick = { step = 1 },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("← ATRÁS") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            scope.launch {
                                AppPreferences(context).saveStealthConfig(config)
                                FridaUtils.configure(config)
                                onInstall(chosenVersion, config)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03A9F4))
                    ) { Text("✓ INSTALAR") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) { Text("✕ CANCELAR") }
                }
            }
        },
        dismissButton = {}
    )
}
