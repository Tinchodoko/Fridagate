package com.hackpuntes.fridagate.ui.frida

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FridaViewModel - Manages the state and business logic for the Frida screen.
 *
 * In MVVM (Model-View-ViewModel) architecture:
 *  - Model      = FridaUtils (data and operations)
 *  - View       = FridaScreen composable (UI, what the user sees)
 *  - ViewModel  = this class (state holder and bridge between Model and View)
 *
 * Why do we need a ViewModel?
 *  - It survives screen rotations (the composable is destroyed and recreated, but the ViewModel is not)
 *  - It separates UI logic from business logic (the composable only reads state and sends events)
 *  - It provides a clean way to run coroutines tied to the screen's lifecycle
 *
 * StateFlow vs LiveData:
 *  We use StateFlow here (instead of LiveData used in the original Frida Launcher)
 *  because StateFlow integrates better with Jetpack Compose.
 *  StateFlow always has a current value (unlike LiveData which can be null initially).
 *  Compose collects StateFlow with "collectAsState()" in the composable.
 */
class FridaViewModel(private val appContext: Context) : ViewModel() {

    private val versionPreferences = appContext.getSharedPreferences("fridagate_settings", Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // UI State — each property below is a piece of state that the UI observes.
    // When any of these change, Compose automatically recomposes the affected UI.
    // -------------------------------------------------------------------------

    /**
     * Whether a background operation is in progress (download, install, etc.).
     * When true, the UI shows a loading indicator and disables buttons.
     *
     * MutableStateFlow: internal, can be changed only from within this ViewModel
     * StateFlow (asStateFlow): exposed to the UI as read-only
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Whether frida-server is installed on the device */
    private val _isServerInstalled = MutableStateFlow(false)
    val isServerInstalled: StateFlow<Boolean> = _isServerInstalled.asStateFlow()

    /** Whether frida-server process is currently running */
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    /** Whether the local Frida control port is listening (not a client handshake test). */
    private val _isServerPortListening = MutableStateFlow(false)
    val isServerPortListening: StateFlow<Boolean> = _isServerPortListening.asStateFlow()

    /** The version of the installed frida-server (e.g., "16.7.0") */
    private val _installedVersion = MutableStateFlow("Unknown")
    val installedVersion: StateFlow<String> = _installedVersion.asStateFlow()

    /** List of available Frida releases fetched from GitHub */
    private val _availableReleases = MutableStateFlow<List<FridaRelease>>(emptyList())
    val availableReleases: StateFlow<List<FridaRelease>> = _availableReleases.asStateFlow()

    /** The version selected by the user in the dropdown */
    // Keep the picker blank until the user explicitly chooses a version.
    private val _selectedVersion = MutableStateFlow("")
    val selectedVersion: StateFlow<String> = _selectedVersion.asStateFlow()

    /** Whether root access is available on the device */
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /**
     * Log messages shown in the terminal-style text area.
     * Each entry is a single line with a timestamp prefix.
     * We use a List so Compose can efficiently detect changes.
     */
    private val _logs = MutableStateFlow(
        versionPreferences.getString("frida_operation_logs", "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(500)
    )
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** The last set of custom flags entered by the user (remembered across sessions) */
    private val _lastCustomFlags = MutableStateFlow("")
    val lastCustomFlags: StateFlow<String> = _lastCustomFlags.asStateFlow()

    // -------------------------------------------------------------------------
    // Initialization — runs once when the ViewModel is first created
    // -------------------------------------------------------------------------

    init {
        // Check root and server status as soon as the screen opens
        checkRootAndStatus()
        // Fetch available versions from GitHub in the background
        loadAvailableReleases()
    }

    // -------------------------------------------------------------------------
    // Public functions — called by the UI in response to user actions
    // -------------------------------------------------------------------------

    /**
     * Checks root availability and current frida-server status.
     * Called on init and when the user taps the Refresh button.
     *
     * viewModelScope.launch starts a coroutine that is automatically
     * cancelled when the ViewModel is cleared (screen is destroyed).
     */
    fun checkRootAndStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Checking root access and server status...")

            // Check root access
            val rootAvailable = RootUtils.isRootAvailable()
            _isRootAvailable.value = rootAvailable

            if (!rootAvailable) {
                addLog("Root access NOT available — some features will be disabled")
                _isLoading.value = false
                return@launch
            }

            addLog("Root access available")

            // Check if frida-server binary exists
            val isInstalled = FridaUtils.isFridaServerInstalled()
            _isServerInstalled.value = isInstalled

            if (isInstalled) {
                // Read the installed version from the version file
                val version = FridaUtils.getInstalledFridaVersion()
                _installedVersion.value = version ?: "Unknown"

                // Check if the process is currently running
                val isRunning = FridaUtils.isFridaServerRunning()
                _isServerRunning.value = isRunning
                _isServerPortListening.value = FridaUtils.isFridaServerPortListening()

                val statusText = if (isRunning) "running" else "stopped"
                addLog("Puerto local 27042: " + if (_isServerPortListening.value) "LISTENING" else "no confirmado")
                addLog("Frida server ${_installedVersion.value} is installed and $statusText")
            } else {
                addLog("Frida server is not installed")
                _isServerRunning.value = false
                _isServerPortListening.value = false
                _installedVersion.value = "Not installed"
            }

            _isLoading.value = false
        }
    }

    /**
     * Fetches the list of available Frida releases from the GitHub API.
     * Populates the version dropdown in the UI.
     */
    fun loadAvailableReleases() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("BUSCANDO VERSIÓN RECOMENDADA Y ÚLTIMA VERSIÓN ESTABLE DE FRIDA...")
            val fetched = FridaUtils.getAvailableFridaReleases()
            val requestedVersions = listOf("17.23.3", "17.6.0", "16.7.19")
            _availableReleases.value = requestedVersions.map { version ->
                fetched.find { it.version == version } ?: FridaRelease(
                    version = version,
                    releaseDate = "",
                    assets = listOf("arm", "arm64", "x86", "x86_64").map { arch ->
                        FridaUtils.FridaAsset(
                            name = "frida-server-$version-android-$arch.xz",
                            downloadUrl = "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-$arch.xz",
                            architecture = arch,
                            size = 0L
                        )
                    }
                )
            }
            // Do not auto-select a version: require an explicit choice each time the app opens.
            addLog("VERSIONES DISPONIBLES: " + requestedVersions.joinToString(", "))
            _isLoading.value = false
        }
    }

    private fun isValidStableVersion(version: String): Boolean =
        version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))

    /** Updates the selected version when the user picks a release from the list. */
    fun setSelectedVersion(version: String) {
        if (_availableReleases.value.any { it.version == version }) {
            _selectedVersion.value = version
            versionPreferences.edit().putString("selected_frida_version", version).apply()
        }
    }

    /** Allows a custom stable Frida version, provided it supports modern Android SELinux policy formats. */
    fun setCustomVersion(version: String) {
        if (!isValidStableVersion(version)) {
            _selectedVersion.value = ""
            versionPreferences.edit().remove("selected_frida_version").apply()
            return
        }
        _selectedVersion.value = version
        versionPreferences.edit().putString("selected_frida_version", version).apply()
        addLog("VERSIÓN PERSONALIZADA SELECCIONADA: $version")
    }

    /** Downloads and installs frida-server only. frida-inject has its own button. */
    fun downloadAndInstall(context: Context) {
        viewModelScope.launch {
            val version = _selectedVersion.value
            if (version.isBlank()) {
                addLog("ERROR: SELECCIONA UNA VERSIÓN DE FRIDA ANTES DE INSTALAR SERVER.")
                return@launch
            }
            val architecture = FridaUtils.getDeviceArchitecture()
            _isLoading.value = true
            addLog("INICIO DE INSTALACIÓN DE FRIDA-SERVER $version ($architecture)")
            try {
                val serverUrl = FridaUtils.getFridaServerUrl(version, architecture)
                if (serverUrl == null) {
                    addLog("ERROR: NO SE ENCONTRÓ LA DESCARGA DE FRIDA-SERVER $version ($architecture)")
                    return@launch
                }
                addLog("DESCARGANDO FRIDA-SERVER $version...")
                val serverFile = FridaUtils.downloadFridaServerFromUrl(context, serverUrl)
                if (serverFile == null) {
                    addLog("ERROR: NO SE PUDO DESCARGAR FRIDA-SERVER")
                    return@launch
                }
                val serverInstalled = try {
                    FridaUtils.installFridaServer(serverFile, version)
                } finally {
                    serverFile.delete()
                }
                if (!serverInstalled) {
                    addLog("ERROR: FALLÓ LA INSTALACIÓN DE FRIDA-SERVER")
                    return@launch
                }
                _isServerInstalled.value = true
                _installedVersion.value = version
                _isServerRunning.value = FridaUtils.isFridaServerRunning()
                addLog("FRIDA-SERVER $version INSTALADO CORRECTAMENTE")
                addLog("FRIDA-INJECT NO SE MODIFICÓ. USA SU BOTÓN PARA INSTALARLO POR SEPARADO.")
            } catch (e: Exception) {
                addLog("ERROR AL INSTALAR FRIDA-SERVER: ${e.message ?: "ERROR DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Starts frida-server with default settings (no extra flags) */
    fun startServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Starting frida-server...")

            val started = FridaUtils.startFridaServer()

            if (started) {
                _isServerRunning.value = true
                addLog("Frida server started successfully")
            } else {
                addLog("ERROR: Failed to start frida-server")
            }

            _isLoading.value = false
        }
    }

    /**
     * Starts frida-server with custom command-line flags.
     *
     * Sanitizes the input first to remove shell injection characters
     * like ; | & $ which could be used to run arbitrary commands.
     *
     * @param flags Raw flags string entered by the user
     */
    fun startServerWithCustomFlags(flags: String) {
        // Remove potentially dangerous shell characters before passing to su
        val sanitized = flags.replace(Regex("[;&|<>$`\\\\]"), "").trim()
        _lastCustomFlags.value = sanitized

        viewModelScope.launch {
            _isLoading.value = true
            addLog("Starting frida-server with flags: $sanitized")

            val started = FridaUtils.startFridaServerWithFlags(sanitized)

            if (started) {
                _isServerRunning.value = true
                addLog("Frida server started with flags: $sanitized")
            } else {
                addLog("ERROR: Failed to start frida-server with flags")
            }

            _isLoading.value = false
        }
    }

    /** Stops the running frida-server process */
    fun stopServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Stopping frida-server...")

            val stopped = FridaUtils.stopFridaServer()

            if (stopped) {
                _isServerRunning.value = false
                addLog("Frida server stopped successfully")
            } else {
                addLog("ERROR: Failed to stop frida-server")
            }

            _isLoading.value = false
        }
    }

    /** Removes Frida processes, binaries, version markers and injection tracking files. */
    fun uninstallServer(onFinished: (() -> Unit)? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("=== INICIANDO DESINSTALACIÓN Y LIMPIEZA COMPLETA DE FRIDA ===")
            try {
                addLog("Deteniendo primero las aplicaciones y las inyecciones registradas...")
                FridaInjectUtils.stopAllInjections().forEach { addLog(it) }
                val output = withContext(Dispatchers.IO) {
                    RootUtils.executeSuCommand(
                    """
                    echo "=== Matando procesos frida activos ==="
                    pkill -9 -x frida-server 2>/dev/null || true
                    pkill -9 -x frida-inject 2>/dev/null || true

                    echo "=== Borrando binarios y versiones en /data/local/tmp ==="
                    rm -f /data/local/tmp/frida-server
                    rm -f /data/local/tmp/frida-version.txt
                    rm -f /data/local/tmp/frida-inject
                    rm -f /data/local/tmp/frida-inject-version.txt
                    rm -f /data/local/tmp/fridagate_inject.log
                    rm -f /data/local/tmp/fridagate_inject.pid
                    rm -f /data/local/tmp/fridagate_injected_packages.txt
                    rm -f /data/local/tmp/fridagate_enabled_scripts.js
                    rm -f /data/local/tmp/fridagate_combined.js
                    rm -f /data/local/tmp/fridagate_custom.js
                    rm -f /data/local/tmp/fridagate_custom.log

                    echo "=== Verificando que no quede nada ==="
                    ls -la /data/local/tmp/ | grep -i frida || echo "No quedan archivos con frida en el nombre"
                    ps -A | grep -iE '[f]rida-server|[f]rida-inject' || echo "No quedan procesos frida activos"
                    echo "=== Limpieza completa ==="
                    """.trimIndent()
                    )
                }
                if (output.isBlank()) {
                    addLog("ERROR: la limpieza no devolvió salida; comprueba que el acceso root esté autorizado.")
                } else {
                    output.lineSequence().filter { it.isNotBlank() }.forEach { addLog(it) }
                }
                _isServerInstalled.value = FridaUtils.isFridaServerInstalled()
                _isServerRunning.value = FridaUtils.isFridaServerRunning()
                _installedVersion.value = FridaUtils.getInstalledFridaVersion() ?: "Not installed"
                addLog(
                    if (!_isServerInstalled.value && !FridaInjectUtils.isFridaInjectInstalled())
                        "DESINSTALACIÓN COMPLETA: SERVER E INJECT YA NO ESTÁN INSTALADOS."
                    else
                        "ADVERTENCIA: QUEDAN COMPONENTES INSTALADOS; REVISA LA VERIFICACIÓN ANTERIOR."
                )
            } catch (e: Exception) {
                addLog("ERROR DURANTE LA LIMPIEZA: ${e.message ?: "ERROR DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
                onFinished?.invoke()
            }
        }
    }

    /** Clears all log entries from the log panel */
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Appends a new line to the log with a timestamp prefix.
     *
     * Uses System.currentTimeMillis() to get the current time,
     * formats it as HH:mm:ss (hours:minutes:seconds).
     *
     * @param message The log message to append
     */
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val updated = (_logs.value + "[$time] $message").takeLast(500)
        _logs.value = updated
        versionPreferences.edit().putString("frida_operation_logs", updated.joinToString("\n")).apply()
    }

    /** Adds a persistent operation log line from related components such as frida-inject. */
    fun logOperation(message: String) = addLog(message)

    /**
     * Called automatically when the ViewModel is about to be destroyed.
     * Used to release the persistent root shell process.
     */
    override fun onCleared() {
        super.onCleared()
        RootUtils.closeSuProcess()
    }
}
