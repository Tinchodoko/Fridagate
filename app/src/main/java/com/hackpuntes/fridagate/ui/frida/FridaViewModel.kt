package com.hackpuntes.fridagate.ui.frida

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.ProxyUtils
import com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** The version of the installed frida-server (e.g., "16.7.0") */
    private val _installedVersion = MutableStateFlow("Unknown")
    val installedVersion: StateFlow<String> = _installedVersion.asStateFlow()

    /** List of available Frida releases fetched from GitHub */
    private val _availableReleases = MutableStateFlow<List<FridaRelease>>(emptyList())
    val availableReleases: StateFlow<List<FridaRelease>> = _availableReleases.asStateFlow()

    /** The version selected by the user in the dropdown */
    private val _selectedVersion = MutableStateFlow(versionPreferences.getString("selected_frida_version", "17.6.0") ?: "17.6.0")
    val selectedVersion: StateFlow<String> = _selectedVersion.asStateFlow()

    /** Whether root access is available on the device */
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /**
     * Log messages shown in the terminal-style text area.
     * Each entry is a single line with a timestamp prefix.
     * We use a List so Compose can efficiently detect changes.
     */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
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

                val statusText = if (isRunning) "running" else "stopped"
                addLog("Frida server ${_installedVersion.value} is installed and $statusText")
            } else {
                addLog("Frida server is not installed")
                _isServerRunning.value = false
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
            val compatible = fetched.filter { isAndroid16CompatibleVersion(it.version) }
            val recommendedVersion = "17.6.0"
            val recommended = compatible.find { it.version == recommendedVersion } ?: FridaRelease(
                version = recommendedVersion,
                releaseDate = "",
                assets = listOf("arm", "arm64", "x86", "x86_64").map { arch ->
                    FridaUtils.FridaAsset(
                        name = "frida-server-$recommendedVersion-android-$arch.xz",
                        downloadUrl = "https://github.com/frida/frida/releases/download/$recommendedVersion/frida-server-$recommendedVersion-android-$arch.xz",
                        architecture = arch,
                        size = 0L
                    )
                }
            )
            val latest = compatible.firstOrNull()
            _availableReleases.value = if (latest != null && latest.version != recommendedVersion) {
                listOf(recommended, latest)
            } else {
                listOf(recommended)
            }
            // Preserve the user's selection across screens and app restarts.
            val savedSelection = versionPreferences.getString("selected_frida_version", recommendedVersion)
                ?: recommendedVersion
            _selectedVersion.value = if (_availableReleases.value.any { it.version == savedSelection }) {
                savedSelection
            } else {
                recommendedVersion
            }
            versionPreferences.edit().putString("selected_frida_version", _selectedVersion.value).apply()
            addLog("VERSIÓN SELECCIONADA: " + _selectedVersion.value)
            latest?.takeIf { it.version != recommendedVersion }?.let {
                addLog("ÚLTIMA VERSIÓN ESTABLE DISPONIBLE: ${it.version}")
            }
            _isLoading.value = false
        }
    }

    private fun isAndroid16CompatibleVersion(version: String): Boolean {
        // Ignore prereleases; compare numeric components rather than lexical strings.
        if ('-' in version) return false
        val parts = version.split(".").map { it.toIntOrNull() ?: -1 }
        if (parts.size < 3 || parts.any { it < 0 }) return false
        val minimum = listOf(17, 6, 0)
        for (i in minimum.indices) {
            val current = parts.getOrElse(i) { 0 }
            if (current != minimum[i]) return current > minimum[i]
        }
        return true
    }

    /** Updates the selected version when the user picks a release from the list. */
    fun setSelectedVersion(version: String) {
        if (_availableReleases.value.any { it.version == version }) {
            _selectedVersion.value = version
            versionPreferences.edit().putString("selected_frida_version", version).apply()
        }
    }

    /** Allows a custom stable Frida version, provided it supports modern Android SELinux policy formats. */
    fun setCustomVersion(version: String) {
        if (!isAndroid16CompatibleVersion(version)) {
            addLog("VERSIÓN NO COMPATIBLE: usa Frida 17.6.0 o posterior estable para Android 16.")
            return
        }
        _selectedVersion.value = version
        versionPreferences.edit().putString("selected_frida_version", version).apply()
        addLog("VERSIÓN PERSONALIZADA SELECCIONADA: $version")
    }

    /**
     * Downloads and installs matching frida-server and frida-inject binaries.
     * Keeping both components on exactly the same version avoids protocol mismatches.
     */
    fun downloadAndInstall(context: Context) {
        viewModelScope.launch {
            val version = _selectedVersion.value
            val architecture = FridaUtils.getDeviceArchitecture()
            _isLoading.value = true
            try {
                addLog("INSTALACIÓN CONJUNTA DE FRIDA-SERVER Y FRIDA-INJECT $version ($architecture)")

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
                addLog("FRIDA-SERVER $version INSTALADO CORRECTAMENTE")

                addLog("DESCARGANDO E INSTALANDO FRIDA-INJECT $version...")
                val injectInstalled = FridaInjectUtils.downloadAndInstall(context, version)
                if (!injectInstalled) {
                    addLog("ERROR: FRIDA-SERVER QUEDÓ INSTALADO, PERO FRIDA-INJECT NO SE PUDO INSTALAR. REVISA LA CONEXIÓN Y VUELVE A INTENTAR.")
                    return@launch
                }
                addLog("FRIDA-INJECT $version INSTALADO CORRECTAMENTE")
                addLog("INSTALACIÓN COMPLETA: FRIDA-SERVER Y FRIDA-INJECT USAN LA VERSIÓN $version")
            } catch (e: Exception) {
                addLog("ERROR EN LA INSTALACIÓN CONJUNTA: ${e.message ?: "ERROR DESCONOCIDO"}")
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

    /** Removes Frida server and injector, stops tracked injections, and clears proxy settings. */
    fun uninstallServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Desinstalando todos los componentes de Frida...")
            try {
                FridaInjectUtils.stopAllInjections().forEach { addLog(it) }

                addLog("Deteniendo frida-server...")
                FridaUtils.stopFridaServer()

                addLog("Eliminando frida-inject...")
                val injectRemoved = FridaInjectUtils.uninstallFridaInject()
                addLog(if (injectRemoved) "✓ frida-inject desinstalado" else "ADVERTENCIA: no se pudo confirmar la eliminación de frida-inject")

                addLog("Eliminando frida-server...")
                val serverRemoved = FridaUtils.uninstallFridaServer()
                addLog(if (serverRemoved) "✓ frida-server desinstalado" else "ERROR: no se pudo eliminar frida-server")

                addLog("Restaurando la configuración de red...")
                val previousProxy = ProxyUtils.getSystemProxy()
                val previousHost = previousProxy?.substringBeforeLast(':')
                val previousPort = previousProxy?.substringAfterLast(':')?.toIntOrNull()
                val iptablesRemoved = if (previousHost != null && previousPort != null) {
                    ProxyUtils.disableIptablesProxy(previousHost, previousPort, previousPort)
                } else {
                    ProxyUtils.disableIptablesProxy()
                }
                val proxyRemoved = ProxyUtils.clearSystemProxy()
                addLog(if (proxyRemoved) "✓ Proxy global de Android eliminado" else "ADVERTENCIA: no se pudo confirmar la limpieza del proxy global")
                addLog(if (iptablesRemoved) "✓ Reglas iptables de FridaGate eliminadas" else "ADVERTENCIA: no se pudo confirmar la limpieza de iptables")

                _isServerInstalled.value = !serverRemoved
                _isServerRunning.value = FridaUtils.isFridaServerRunning()
                _installedVersion.value = if (serverRemoved) "Not installed" else "Unknown"
                addLog(if (serverRemoved && injectRemoved) "Desinstalación completa de Frida finalizada." else "Desinstalación finalizada con advertencias; revisa los mensajes anteriores.")
            } catch (_: Exception) {
                addLog("ERROR durante la desinstalación completa de Frida.")
            } finally {
                _isLoading.value = false
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
        // Create a new list with the new entry appended
        // We never mutate the existing list — StateFlow needs a new object to detect the change
        _logs.value = _logs.value + "[$time] $message"
    }

    /**
     * Called automatically when the ViewModel is about to be destroyed.
     * Used to release the persistent root shell process.
     */
    override fun onCleared() {
        super.onCleared()
        RootUtils.closeSuProcess()
    }
}
