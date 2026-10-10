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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.ProxyUtils
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
 * This ViewModel reads from both FridaUtils and ProxyUtils to give the user
 * a single view of the entire interception setup state.
 *
 * It also provides "ACTIVATE ALL" and "DEACTIVATE ALL" — convenience actions
 * that run the full setup or teardown sequence in a single coroutine.
 *
 * We define it here (in the same file as the screen) because it is only
 * used by DashboardScreen. When a ViewModel grows large, move it to its own file.
 */
class DashboardViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)

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

    /** Whether iptables proxy rules are active */
    private val _isProxyActive = MutableStateFlow(false)
    val isProxyActive: StateFlow<Boolean> = _isProxyActive.asStateFlow()

    /** Whether Burp Suite is reachable at the saved IP/port */
    private val _isBurpReachable = MutableStateFlow(false)
    val isBurpReachable: StateFlow<Boolean> = _isBurpReachable.asStateFlow()

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
            addLog("Refreshing system status...")

            // Check root
            val root = RootUtils.isRootAvailable()
            _isRootAvailable.value = root

            // Check Frida
            val fridaInstalled = FridaUtils.isFridaServerInstalled()
            _isFridaInstalled.value = fridaInstalled
            val fridaRunning = if (fridaInstalled) FridaUtils.isFridaServerRunning() else false
            _isFridaRunning.value = fridaRunning

            // Check proxy
            val proxyActive = ProxyUtils.isIptablesProxyEnabled()
            _isProxyActive.value = proxyActive

            // Check Burp reachability using saved settings
            val ip = prefs.burpIp.first()
            val port = prefs.burpHttpPort.first()
            val burpReachable = ProxyUtils.isBurpReachable(ip, port)
            _isBurpReachable.value = burpReachable

            addLog("Root: $root | Frida: $fridaRunning | Proxy: $proxyActive | Burp: $burpReachable")
            _isLoading.value = false
        }
    }

    // ── One-tap actions ───────────────────────────────────────────────────────

    /**
     * Runs the full interception setup in sequence:
     *  1. Start frida-server (bypasses SSL pinning)
     *  2. Enable iptables proxy (redirects all traffic to Burp)
     *
     * Uses a single coroutine so steps run in order, not in parallel.
     * Each step is logged so the user can follow the progress.
     */
    fun activateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("── ACTIVATE ALL ──────────────────")

            // Step 1: Start frida-server
            if (!_isFridaInstalled.value) {
                addLog("ERROR: Frida server is not installed — install it from the Frida tab first")
                _isLoading.value = false
                return@launch
            }

            if (_isFridaRunning.value) {
                addLog("Frida server already running — skipping")
            } else {
                addLog("Starting frida-server...")
                val started = FridaUtils.startFridaServer()
                _isFridaRunning.value = started
                if (started) addLog("Frida server started") else addLog("ERROR: Failed to start frida-server")
            }

            // Step 2: Enable iptables proxy
            val ip = prefs.burpIp.first()
            val httpPort = prefs.burpHttpPort.first()
            val httpsPort = prefs.burpHttpsPort.first()

            addLog("Enabling iptables proxy → $ip:$httpPort...")
            val proxyEnabled = ProxyUtils.enableIptablesProxy(ip, httpPort, httpsPort)
            _isProxyActive.value = proxyEnabled
            if (proxyEnabled) addLog("iptables proxy enabled") else addLog("ERROR: Failed to enable proxy")

            // Step 3: Verify Burp is reachable
            addLog("Checking Burp Suite at $ip:$httpPort...")
            val burpReachable = ProxyUtils.isBurpReachable(ip, httpPort)
            _isBurpReachable.value = burpReachable
            if (burpReachable) {
                addLog("Burp reachable — interception is ACTIVE")
            } else {
                addLog("WARNING: Burp not reachable — make sure Burp is running on your PC")
            }

            addLog("── DONE ──────────────────────────")
            _isLoading.value = false
        }
    }

    /**
     * Tears down the interception setup in sequence:
     *  1. Stop frida-server
     *  2. Disable iptables proxy
     */
    /**
     * Instala y prepara en secuencia los componentes principales de FridaGate.
     * Requiere root y conexión a Internet. Usa la misma versión para server e inject.
     */
    fun smartInstallAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                addLog("── INSTALACIÓN INTELIGENTE ─────────")
                if (!RootUtils.isRootAvailable()) {
                    _isRootAvailable.value = false
                    addLog("ERROR: Se necesita acceso root para instalar Frida y configurar el proxy")
                    return@launch
                }
                _isRootAvailable.value = true

                val ip = prefs.burpIp.first()
                val httpPort = prefs.burpHttpPort.first()
                val httpsPort = prefs.burpHttpsPort.first()

                // Remove a stale proxy first so downloads are not sent to an unavailable Burp listener.
                addLog("Limpiando cualquier proxy anterior antes de descargar...")
                val oldRulesCleared = ProxyUtils.disableIptablesProxy(ip, httpPort, httpsPort)
                val oldSystemProxyCleared = ProxyUtils.clearSystemProxy()
                if (oldRulesCleared && oldSystemProxyCleared) {
                    addLog("Proxy anterior desactivado; conexión normal restaurada")
                } else {
                    addLog("ADVERTENCIA: no se pudo confirmar la limpieza completa del proxy anterior")
                }

                val architecture = FridaUtils.getDeviceArchitecture()
                addLog("Buscando la última versión de Frida compatible con $architecture...")
                val releases = FridaUtils.getAvailableFridaReleases()
                val version = releases.firstOrNull { release ->
                    release.assets.any { it.architecture == architecture }
                }?.version
                if (version == null) {
                    addLog("ERROR: No se encontraron versiones de Frida para la arquitectura $architecture")
                    return@launch
                }
                addLog("Versión seleccionada: $version")

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
                addLog("frida-server iniciado")

                // Do not redirect traffic until the Burp listener is reachable.
                addLog("Comprobando Burp Suite en $ip:$httpPort antes de activar el proxy...")
                _isBurpReachable.value = ProxyUtils.isBurpReachable(ip, httpPort)
                if (!_isBurpReachable.value) {
                    ProxyUtils.disableIptablesProxy(ip, httpPort, httpsPort)
                    ProxyUtils.clearSystemProxy()
                    _isProxyActive.value = false
                    addLog("ADVERTENCIA: Burp no responde en $ip:$httpPort.")
                    addLog("Proxy NO activado para evitar dejar el teléfono sin Internet.")
                    addLog("Frida quedó instalado y ejecutándose; inicia Burp y verifica IP/puerto para configurar el proxy después.")
                    addLog("── INSTALACIÓN INTELIGENTE FINALIZADA (proxy omitido) ──")
                    return@launch
                }

                addLog("Burp responde. Activando proxy iptables hacia $ip:$httpPort...")
                _isProxyActive.value = ProxyUtils.enableIptablesProxy(ip, httpPort, httpsPort)
                if (!_isProxyActive.value) {
                    ProxyUtils.clearSystemProxy()
                    addLog("ADVERTENCIA: no se pudo activar iptables; el proxy del sistema se deja desactivado.")
                    addLog("── INSTALACIÓN INTELIGENTE FINALIZADA (proxy omitido) ──")
                    return@launch
                }
                addLog("Proxy iptables activado")

                addLog("Configurando proxy del sistema...")
                val systemProxySet = ProxyUtils.setSystemProxy(ip, httpPort)
                if (systemProxySet) addLog("Proxy del sistema configurado")
                else addLog("ADVERTENCIA: no se pudo configurar el proxy del sistema")

                addLog("Intentando instalar el certificado CA de Burp Suite...")
                val certificateInstalled = ProxyUtils.installBurpCertificate(ip, httpPort)
                if (certificateInstalled) addLog("Certificado CA de Burp Suite instalado")
                else addLog("ADVERTENCIA: no se instaló el certificado CA; verifica el listener de Burp y el almacenamiento del sistema")

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
            addLog("── DEACTIVATE ALL ────────────────")

            // Step 1: Stop frida-server
            if (_isFridaRunning.value) {
                addLog("Stopping frida-server...")
                val stopped = FridaUtils.stopFridaServer()
                _isFridaRunning.value = !stopped
                if (stopped) addLog("Frida server stopped") else addLog("ERROR: Failed to stop frida-server")
            } else {
                addLog("Frida server not running — skipping")
            }

            // Step 2: Disable iptables proxy
            addLog("Disabling iptables proxy...")
            val disabled = ProxyUtils.disableIptablesProxy(prefs.burpIp.first(), prefs.burpHttpPort.first(), prefs.burpHttpsPort.first())
            _isProxyActive.value = !disabled
            if (disabled) addLog("iptables proxy disabled") else addLog("ERROR: Failed to disable proxy")

            // Step 3: Clear system proxy (http_proxy + global_http_proxy)
            // Without this, the proxy setting persists across reboots and the WiFi shows "no internet"
            addLog("Clearing system proxy...")
            ProxyUtils.clearSystemProxy()
            addLog("System proxy cleared")

            _isBurpReachable.value = false
            addLog("── DONE ──────────────────────────")
            _isLoading.value = false
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(context) as T
            }
        }
    )

    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val isFridaInstalled by viewModel.isFridaInstalled.collectAsState()
    val isFridaRunning by viewModel.isFridaRunning.collectAsState()
    val isProxyActive by viewModel.isProxyActive.collectAsState()
    val isBurpReachable by viewModel.isBurpReachable.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var hasStoragePermission by remember { mutableStateOf(storagePermissionGranted(context)) }
    var hasNotificationPermission by remember { mutableStateOf(notificationPermissionGranted(context)) }
    var hasBackgroundPermission by remember { mutableStateOf(backgroundExecutionAllowed(context)) }
    var isNotificationVisible by remember { mutableStateOf(notificationIsVisible(context)) }
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
                hasBackgroundPermission = backgroundExecutionAllowed(context)
                isNotificationVisible = notificationIsVisible(context)
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
                rootGranted = isRootAvailable,
                backgroundGranted = hasBackgroundPermission,
                batteryExempt = isIgnoringBatteryOptimizations,
                onRequestStorage = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName)))
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        requestLegacyStorage.launch(arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ))
                    }
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                onRequestRoot = { viewModel.refreshStatus() },
                onRequestBackground = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)))
                },
                onRequestBattery = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + context.packageName)))
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
                isProxyActive = isProxyActive,
                isBurpReachable = isBurpReachable,
                hasStoragePermission = hasStoragePermission,
                hasNotificationPermission = hasNotificationPermission,
                hasBackgroundPermission = hasBackgroundPermission,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations
            )

            // ── One-tap action buttons ────────────────────────────────────────
            OneTabActionsCard(
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onActivateAll = { viewModel.activateAll() },
                onSmartInstall = { viewModel.smartInstallAll() },
                onDeactivateAll = { viewModel.deactivateAll() },
                onRefresh = { viewModel.refreshStatus() },
                isNotificationVisible = isNotificationVisible,
                canShowNotification = hasNotificationPermission,
                onToggleNotification = {
                    if (isNotificationVisible) {
                        context.startService(Intent(context, com.hackpuntes.fridagate.FridaGateNotificationService::class.java).apply {
                            action = com.hackpuntes.fridagate.FridaGateNotificationService.ACTION_HIDE
                        })
                        isNotificationVisible = false
                    } else if (hasNotificationPermission) {
                        ContextCompat.startForegroundService(context,
                            Intent(context, com.hackpuntes.fridagate.FridaGateNotificationService::class.java))
                        isNotificationVisible = true
                    } else {
                        if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            )

            // ── Log panel ─────────────────────────────────────────────────────
            DashboardLogPanel(
                logs = logs,
                onClear = { viewModel.clearLogs() }
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

private fun storagePermissionGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
         ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)

private fun notificationIsVisible(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .activeNotifications.any { it.id == 2200 }
        }.getOrDefault(false)
    } else false

private fun notificationPermissionGranted(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()

private fun backgroundExecutionAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
        !(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

private fun batteryOptimizationIgnored(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

@Composable
private fun PermissionControlsCard(
    storageGranted: Boolean,
    notificationsGranted: Boolean,
    rootGranted: Boolean,
    backgroundGranted: Boolean,
    batteryExempt: Boolean,
    onRequestStorage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestRoot: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestBattery: () -> Unit
) {
    val missingAny = !storageGranted || !notificationsGranted || !rootGranted || !backgroundGranted || !batteryExempt
    if (!missingAny) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permisos y ejecución", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Las opciones desaparecen automáticamente cuando el sistema confirma el permiso.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!storageGranted) OutlinedButton(onClick = onRequestStorage, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir acceso al almacenamiento")
            }
            if (!notificationsGranted) OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir notificaciones")
            }
            if (!rootGranted) OutlinedButton(onClick = onRequestRoot, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir acceso root")
            }
            if (!backgroundGranted) OutlinedButton(onClick = onRequestBackground, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir ejecución en segundo plano")
            }
            if (!batteryExempt) OutlinedButton(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
                Text("Desactivar optimización de batería")
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
    isProxyActive: Boolean,
    isBurpReachable: Boolean,
    hasStoragePermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Estado del sistema",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider()

            // Each StatusIndicatorRow shows one component's status
            StatusIndicatorRow(label = "Frida Installed", active = isFridaInstalled, activeText = "Sí", inactiveText = "No")
            StatusIndicatorRow(label = "Frida Running", active = isFridaRunning, activeText = "En ejecución", inactiveText = "Detenido")
            StatusIndicatorRow(label = "Proxy (iptables)", active = isProxyActive, activeText = "Activo", inactiveText = "Inactivo")
            StatusIndicatorRow(label = "Burp reachable", active = isBurpReachable, activeText = "Sí", inactiveText = "No")
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            StatusIndicatorRow(label = "Permiso de almacenamiento", active = hasStoragePermission, activeText = "Concedido", inactiveText = "Pendiente")
            StatusIndicatorRow(label = "Permiso de notificaciones", active = hasNotificationPermission, activeText = "Concedido", inactiveText = "Pendiente")
            StatusIndicatorRow(label = "Acceso root", active = isRootAvailable, activeText = "Concedido", inactiveText = "No disponible")
            StatusIndicatorRow(label = "Restricciones en segundo plano", active = !hasBackgroundPermission, activeText = "Sí", inactiveText = "No", activeColor = Color(0xFFF44336), inactiveColor = Color(0xFF4CAF50))
            StatusIndicatorRow(label = "Optimización de batería", active = isIgnoringBatteryOptimizations, activeText = "Desactivada", inactiveText = "Activa")
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Colored dot indicator
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
    onActivateAll: () -> Unit,
    onSmartInstall: () -> Unit,
    onDeactivateAll: () -> Unit,
    onRefresh: () -> Unit,
    isNotificationVisible: Boolean,
    canShowNotification: Boolean,
    onToggleNotification: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Acciones rápidas",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "ACTIVAR TODO inicia frida-server y habilita el proxy iptables con un solo toque.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            // ACTIVATE ALL — green, prominent
            Button(
                onClick = onActivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(
                    text = "▶  ACTIVAR TODO",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // SMART INSTALL — installs Frida server + inject and configures the proxy
            Button(
                onClick = onSmartInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "⚙  INSTALACIÓN INTELIGENTE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // DEACTIVATE ALL — red
            Button(
                onClick = onDeactivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text(
                    text = "■  DESACTIVAR TODO",
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
                onClick = onToggleNotification,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && (canShowNotification || isNotificationVisible)
            ) {
                Text(if (isNotificationVisible) "OCULTAR NOTIFICACIÓN" else "MOSTRAR NOTIFICACIÓN")
            }
        }
    }
}

/**
 * Log panel for the dashboard — same pattern as the other screens.
 */
@Composable
private fun DashboardLogPanel(logs: List<String>, onClear: () -> Unit) {
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
                Text("Registro", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClear) { Text("Limpiar") }
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
                    Text("Todavía no hay registros...", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                // White/grey for dashboard logs — neutral, not Frida green or Proxy blue
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
