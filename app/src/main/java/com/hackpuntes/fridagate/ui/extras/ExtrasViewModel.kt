package com.hackpuntes.fridagate.ui.extras

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.ScriptUtils
import com.hackpuntes.fridagate.utils.ScriptUtils.BypassScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExtrasViewModel(private val context: Context) : ViewModel() {

    data class AppInfo(val name: String, val packageName: String)

    val scripts: List<BypassScript> = ScriptUtils.SCRIPTS

    // -------------------------------------------------------------------------
    // UI State
    // -------------------------------------------------------------------------

    private val _targetPackage = MutableStateFlow("")
    val targetPackage: StateFlow<String> = _targetPackage.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    /** IDs of scripts currently toggled ON */
    private val _enabledScripts = MutableStateFlow(emptySet<String>())
    val enabledScripts: StateFlow<Set<String>> = _enabledScripts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isFridaRunning = MutableStateFlow(false)
    val isFridaRunning: StateFlow<Boolean> = _isFridaRunning.asStateFlow()

    private val _isFridaInjectInstalled = MutableStateFlow(false)
    val isFridaInjectInstalled: StateFlow<Boolean> = _isFridaInjectInstalled.asStateFlow()

    private val _fridaInjectVersion = MutableStateFlow<String?>(null)
    val fridaInjectVersion: StateFlow<String?> = _fridaInjectVersion.asStateFlow()

    // Avoid repeating environment status lines every time the Scripts screen resumes.
    private var lastLoggedServerRunning: Boolean? = null
    private var environmentCheckInProgress = false

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    init {
        checkEnvironment()
        loadInstalledApps()
    }

    // -------------------------------------------------------------------------
    // Public functions
    // -------------------------------------------------------------------------

    fun setTargetPackage(pkg: String) {
        _targetPackage.value = pkg
    }

    /** Toggles a predefined script independently; both scripts may be disabled. */
    fun toggleScript(id: String) {
        val current = _enabledScripts.value
        _enabledScripts.value = if (current.contains(id)) current - id else current + id
    }

    /** Downloads frida-inject at the same version as frida-server and mirrors progress to the Frida tab log. */
    fun downloadFridaInject(version: String, onOperationLog: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            fun logOperation(message: String) {
                addLog(message)
                onOperationLog?.invoke(message)
            }
            try {
                logOperation("INICIO DE INSTALACIÓN DE FRIDA-INJECT")
                if (!version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) {
                    logOperation("ERROR: SELECCIONA UNA VERSIÓN ESTABLE VÁLIDA.")
                    return@launch
                }
                logOperation("DESCARGANDO FRIDA-INJECT $version (${FridaUtils.getDeviceArchitecture()})...")
                val ok = FridaInjectUtils.downloadAndInstall(context, version)
                if (ok) {
                    _isFridaInjectInstalled.value = true
                    _fridaInjectVersion.value = version
                    logOperation("FRIDA-INJECT $version INSTALADO CORRECTAMENTE.")
                } else {
                    logOperation("ERROR: NO SE PUDO INSTALAR FRIDA-INJECT. COMPRUEBA LA CONEXIÓN Y LOS REGISTROS.")
                }
            } catch (e: Exception) {
                logOperation("ERROR AL INSTALAR FRIDA-INJECT: ${e.message ?: "ERROR DESCONOCIDO"}")
            } finally {
                _isLoading.value = false
                refreshEnvironmentStatus()
            }
        }
    }

    /**
     * Kills the target app, injects all enabled scripts via frida-inject, and relaunches.
     * The log shows real frida-inject output so errors are visible.
     */
    fun launch() {
        val pkg = _targetPackage.value.trim()
        if (pkg.isEmpty()) {
            addLog("ERROR: Enter a target package name (e.g. com.target.app)")
            return
        }
        if (!_isFridaInjectInstalled.value) {
            addLog("ERROR: frida-inject not installed — tap Download first")
            return
        }
        val active = scripts.filter { _enabledScripts.value.contains(it.id) }

        viewModelScope.launch {
            _isLoading.value = true
            if (!_isFridaRunning.value) addLog("WARNING: frida-server not running — start it in the Frida tab")

            val scriptNames = active.joinToString(" + ") { it.name }
            addLog("Injecting [$scriptNames] into $pkg...")

            val resultLines = FridaInjectUtils.launchWithScripts(context, active, pkg)
            resultLines.forEach { addLog(it) }

            _isLoading.value = false
        }
    }

    fun refreshStatus() {
        _logs.value = emptyList()
        checkEnvironment()
    }

    /** Refresh device-side Frida state without clearing the user's logs. */
    fun refreshEnvironmentStatus() {
        checkEnvironment()
    }
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledPackages(0)
                .filter { pkg ->
                    val flags = pkg.applicationInfo?.flags ?: 0
                    (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .map { pkg ->
                    AppInfo(
                        name = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName,
                        packageName = pkg.packageName
                    )
                }
                .sortedBy { it.name.lowercase() }
            _installedApps.value = apps
        }
    }

    private fun checkEnvironment() {
        if (environmentCheckInProgress) return
        environmentCheckInProgress = true
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val running = FridaUtils.isFridaServerRunning()
                val previousRunning = lastLoggedServerRunning
                _isFridaRunning.value = running

                val installed = FridaInjectUtils.isFridaInjectInstalled()
                _isFridaInjectInstalled.value = installed
                val version = if (installed) FridaInjectUtils.getInstalledVersion() else null
                _fridaInjectVersion.value = version

                // Report once on initial startup, then only when server state changes.
                // On a stopped -> running transition, report both server and injector again.
                if (previousRunning == null || previousRunning != running) {
                    addLog(if (running) "frida-server running ✓" else "frida-server not running")
                    if (previousRunning == null || (!previousRunning && running)) {
                        if (installed) {
                            addLog("frida-inject ${version ?: ""} installed ✓")
                        } else {
                            addLog("frida-inject not installed — tap Download")
                        }
                    }
                    lastLoggedServerRunning = running
                }
            } finally {
                _isLoading.value = false
                environmentCheckInProgress = false
            }
        }
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }
}
