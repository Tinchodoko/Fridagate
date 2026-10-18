package com.hackpuntes.fridagate.ui.frida

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.FridaStealthConfig
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FridaViewModel(private val appContext: Context) : ViewModel() {

    private val versionPreferences = appContext.getSharedPreferences("fridagate_settings", Context.MODE_PRIVATE)

    // NUEVO: repositorio de preferencias para la config de camuflaje
    private val appPreferences = AppPreferences(appContext)

    // -------------------------------------------------------------------------
    // UI State
    // -------------------------------------------------------------------------

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isServerInstalled = MutableStateFlow(false)
    val isServerInstalled: StateFlow<Boolean> = _isServerInstalled.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _isServerPortListening = MutableStateFlow(false)
    val isServerPortListening: StateFlow<Boolean> = _isServerPortListening.asStateFlow()

    private val _installedVersion = MutableStateFlow("Unknown")
    val installedVersion: StateFlow<String> = _installedVersion.asStateFlow()

    private val _availableReleases = MutableStateFlow<List<FridaRelease>>(emptyList())
    val availableReleases: StateFlow<List<FridaRelease>> = _availableReleases.asStateFlow()

    private val _selectedVersion = MutableStateFlow("")
    val selectedVersion: StateFlow<String> = _selectedVersion.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _logs = MutableStateFlow(
        versionPreferences.getString("frida_operation_logs", "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(500)
    )
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastCustomFlags = MutableStateFlow("")
    val lastCustomFlags: StateFlow<String> = _lastCustomFlags.asStateFlow()

    // NUEVO: estado de la configuración de camuflaje
    private val _stealthConfig = MutableStateFlow(FridaStealthConfig.DEFAULT)
    val stealthConfig: StateFlow<FridaStealthConfig> = _stealthConfig.asStateFlow()

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    init {
        viewModelScope.launch {
            // NUEVO: cargar la config de camuflaje ANTES de cualquier operación
            val savedConfig = appPreferences.stealthConfig.first()
            _stealthConfig.value = savedConfig
            FridaUtils.configure(savedConfig)

            // Comportamiento original sin cambios
            checkRootAndStatus()
            loadAvailableReleases()
        }
    }

    // -------------------------------------------------------------------------
    // Public functions
    // -------------------------------------------------------------------------

    fun checkRootAndStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Checking root access and server status...")

            val rootAvailable = RootUtils.isRootAvailable()
            _isRootAvailable.value = rootAvailable

            if (!rootAvailable) {
                addLog("Root access NOT available — some features will be disabled")
                _isLoading.value = false
                return@launch
            }

            addLog("Root access available")

            val isInstalled = FridaUtils.isFridaServerInstalled()
            _isServerInstalled.value = isInstalled

            if (isInstalled) {
                val version = FridaUtils.getInstalledFridaVersion()
                _installedVersion.value = version ?: "Unknown"

                val isRunning = FridaUtils.isFridaServerRunning()
                _isServerRunning.value = isRunning
                _isServerPortListening.value = FridaUtils.isFridaServerPortListening()

                val statusText = if (isRunning) "running" else "stopped"
                // CAMBIO: usa el puerto configurado, no 27042 hardcodeado
                addLog("Puerto local ${_stealthConfig.value.port}: " + if (_isServerPortListening.value) "LISTENING" else "no confirmado")
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
            addLog("VERSIONES DISPONIBLES: " + requestedVersions.joinToString(", "))
            _isLoading.value = false
        }
    }

    private fun isValidStableVersion(version: String): Boolean = version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))

    fun setSelectedVersion(version: String) {
        if (_availableReleases.value.any { it.version == version }) {
            _selectedVersion.value = version
            versionPreferences.edit().putString("selected_frida_version", version).apply()
        }
    }

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
            addLog("Directorio destino: ${_stealthConfig.value.serverBinaryPath}")
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
                addLog("Alias del proceso: ${_stealthConfig.value.serverAlias}")
                addLog("FRIDA-INJECT NO SE MODIFICÓ. USA SU BOTÓN PARA INSTALARLO POR SEPARADO.")
            } catch (e: Exception) {
                addLog("ERROR AL INSTALAR FRIDA-SERVER: ${e.message ?: "ERROR DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Starting frida-server...")
            addLog("Puerto: ${_stealthConfig.value.port} | Proceso: ${_stealthConfig.value.serverAlias}")

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

    fun startServerWithCustomFlags(flags: String) {
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

    fun uninstallServer(onFinished: (() -> Unit)? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("=== INICIANDO DESINSTALACIÓN Y LIMPIEZA COMPLETA DE FRIDA ===")
            try {
                addLog("Deteniendo primero las aplicaciones y las inyecciones registradas...")
                FridaInjectUtils.stopAllInjections().forEach { addLog(it) }

                // CAMBIO: usa los alias y rutas de la config activa, no strings hardcodeados
                val sc = _stealthConfig.value
                val output = withContext(Dispatchers.IO) {
                    RootUtils.executeSuCommand(
                        """
                    echo "=== Matando procesos frida activos ==="
                    pkill -9 -x ${sc.serverAlias} 2>/dev/null || true
                    pkill -9 -x ${sc.injectAlias} 2>/dev/null || true

                    echo "=== Borrando binarios y versiones en ${sc.installDir} ==="
                    rm -f ${sc.serverBinaryPath}
                    rm -f ${sc.serverVersionFile}
                    rm -f ${sc.injectBinaryPath}
                    rm -f ${sc.injectVersionFile}
                    rm -f /data/local/tmp/fridagate_inject.log
                    rm -f /data/local/tmp/fridagate_inject.pid
                    rm -f /data/local/tmp/fridagate_injected_packages.txt
                    rm -f /data/local/tmp/fridagate_enabled_scripts.js
                    rm -f /data/local/tmp/fridagate_combined.js
                    rm -f /data/local/tmp/fridagate_custom.js
                    rm -f /data/local/tmp/fridagate_custom.log

                    echo "=== Verificando que no quede nada ==="
                    ls -la ${sc.installDir}/ 2>/dev/null | grep -i "${sc.serverAlias}\|${sc.injectAlias}" || echo "No quedan archivos en ${sc.installDir}"
                    ps -A | grep -iE "[${sc.serverAlias[0]}]${sc.serverAlias.drop(1)}" 2>/dev/null || echo "No quedan procesos activos"
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
                    if (!_isServerInstalled.value && !FridaInjectUtils.isFridaInjectInstalled()) {
                        "DESINSTALACIÓN COMPLETA: SERVER E INJECT YA NO ESTÁN INSTALADOS."
                    } else {
                        "ADVERTENCIA: QUEDAN COMPONENTES INSTALADOS; REVISA LA VERIFICACIÓN ANTERIOR."
                    }
                )
            } catch (e: Exception) {
                addLog("ERROR DURANTE LA LIMPIEZA: ${e.message ?: "ERROR DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
                onFinished?.invoke()
            }
        }
    }

    // -------------------------------------------------------------------------
    // NUEVO: Gestión de configuración de camuflaje
    // -------------------------------------------------------------------------

    /**
     * Valida y aplica una nueva configuración de camuflaje.
     * Si el servidor está corriendo, lo detiene antes de cambiar rutas.
     *
     * @return null si todo OK, o el mensaje de error de validación.
     */
    fun updateStealthConfig(newConfig: FridaStealthConfig): String? {
        val error = newConfig.validate()
        if (error != null) return error

        viewModelScope.launch {
            if (_isServerRunning.value) {
                addLog("Deteniendo servidor antes de cambiar configuración de camuflaje...")
                FridaUtils.stopFridaServer()
                _isServerRunning.value = false
            }

            FridaUtils.configure(newConfig)
            _stealthConfig.value = newConfig
            appPreferences.saveStealthConfig(newConfig)

            addLog("=== Configuración de camuflaje actualizada ===")
            addLog("Proceso server: ${newConfig.serverAlias}")
            addLog("Proceso inject: ${newConfig.injectAlias}")
            addLog("Puerto: ${newConfig.port}")
            addLog("Directorio: ${newConfig.installDir}")

            // Re-verificar si el binario existe en la nueva ruta
            val isInstalled = FridaUtils.isFridaServerInstalled()
            _isServerInstalled.value = isInstalled
            if (isInstalled) {
                _installedVersion.value = FridaUtils.getInstalledFridaVersion() ?: "Unknown"
                addLog("✅ Binario encontrado en la nueva ruta.")
            } else {
                _installedVersion.value = "Not installed"
                addLog("⚠️ Binario NO encontrado en ${newConfig.serverBinaryPath} — reinstalá para aplicar el camuflaje.")
            }
        }
        return null
    }

    /** Resetea la config de camuflaje a los valores originales de Frida. */
    fun resetStealthConfig() {
        viewModelScope.launch {
            updateStealthConfig(FridaStealthConfig.DEFAULT)
            addLog("Config de camuflaje reseteada a valores originales de Frida.")
        }
    }

    // -------------------------------------------------------------------------
    // Log helpers
    // -------------------------------------------------------------------------

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val updated = (_logs.value + "[$time] $message").takeLast(500)
        _logs.value = updated
        versionPreferences.edit().putString("frida_operation_logs", updated.joinToString("\n")).apply()
    }

    fun logOperation(message: String) = addLog(message)

    override fun onCleared() {
        super.onCleared()
        RootUtils.closeSuProcess()
    }
}
