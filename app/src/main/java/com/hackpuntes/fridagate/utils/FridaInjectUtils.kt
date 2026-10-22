package com.hackpuntes.fridagate.utils

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FridaInjectUtils - Manages the frida-inject binary and on-device script injection.
 *
 * CAMBIO: INJECT_BINARY_PATH e INJECT_VERSION_FILE ya no son const val hardcodeadas.
 * Ahora leen dinámicamente desde FridaUtils.getActiveConfig(), respetando la config
 * de camuflaje configurada por el usuario (alias, directorio, puerto).
 */
object FridaInjectUtils {

    // CAMBIO: val con get() en vez de const val — usa la config activa dinámicamente
    val INJECT_BINARY_PATH: String
        get() = FridaUtils.getActiveConfig().injectBinaryPath

    private val INJECT_VERSION_FILE: String
        get() = FridaUtils.getActiveConfig().injectVersionFile

    // Archivos de trabajo en /data/local/tmp — estos no se camuflan (no son ejecutables)
    private const val INJECT_LOG = "/data/local/tmp/fridagate_inject.log"
    private const val INJECTED_PACKAGES_FILE = "/data/local/tmp/fridagate_injected_packages.txt"

    // -------------------------------------------------------------------------
    // Status checks
    // -------------------------------------------------------------------------

    suspend fun isFridaInjectInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("test -x $INJECT_BINARY_PATH && echo FRIDA_INJECT_EXECUTABLE")
                .contains("FRIDA_INJECT_EXECUTABLE")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInstalledVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val binaryVersion = RootUtils.executeSuCommand("$INJECT_BINARY_PATH --version").trim()
            if (Regex("^\\d+\\.\\d+\\.\\d+([.-][A-Za-z0-9.-]+)?$").matches(binaryVersion)) {
                return@withContext binaryVersion
            }
            val metadataVersion = RootUtils.executeSuCommand("cat $INJECT_VERSION_FILE 2>/dev/null").trim()
            metadataVersion.takeIf { Regex("^\\d+\\.\\d+\\.\\d+([.-][A-Za-z0-9.-]+)?$").matches(it) }
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Download and installation
    // -------------------------------------------------------------------------

    suspend fun downloadAndInstall(context: Context, version: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val arch = FridaUtils.getDeviceArchitecture()
            val url = "https://github.com/frida/frida/releases/download/$version/" +
                "frida-inject-$version-android-$arch.xz"

            val downloaded = FridaUtils.downloadFridaServerFromUrl(context, url)
                ?: return@withContext false

            val injectFile = File(context.filesDir, "frida-inject")
            downloaded.renameTo(injectFile)
            injectFile.setExecutable(true)

            // CAMBIO: crear el directorio de destino antes de copiar (puede ser custom)
            val config = FridaUtils.getActiveConfig()
            RootUtils.executeSuCommand("mkdir -p ${config.installDir}")
            RootUtils.executeSuCommand("cp ${injectFile.absolutePath} $INJECT_BINARY_PATH")
            RootUtils.executeSuCommand("chmod 755 $INJECT_BINARY_PATH")
            RootUtils.executeSuCommand("echo '$version' > $INJECT_VERSION_FILE")

            try {
                injectFile.delete()
            } catch (_: Exception) {}
            isFridaInjectInstalled()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readCurrentInjectionLog(): String = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("cat /data/local/tmp/fridagate_inject.log").trimEnd()
        } catch (_: Exception) {
            ""
        }
    }

    // -------------------------------------------------------------------------
    // Launch & inject
    // -------------------------------------------------------------------------

    private suspend fun injectionPrerequisiteError(): String? {
        if (!RootUtils.isRootAvailable()) {
            return "ERROR: Root access is unavailable. Check APatch and grant FridaGate superuser permission."
        }
        if (!isFridaInjectInstalled()) {
            return "ERROR: frida-inject is not installed. Install the injector from the Frida tools section first."
        }
        if (!FridaUtils.isFridaServerRunning()) {
            return "ERROR: frida-server is not running. Start Frida server before launching an app."
        }
        return null
    }

    suspend fun launchWithScripts(
        context: Context,
        scripts: List<ScriptUtils.BypassScript>,
        packageName: String
    ): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            return@withContext listOf("ERROR: Invalid target package name")
        }
        injectionPrerequisiteError()?.let { return@withContext listOf(it) }

        try {
            val scriptPath = if (scripts.size == 1) {
                val deploy = ScriptUtils.saveScriptToDevice(context, scripts.first())
                    ?: return@withContext listOf("ERROR: Could not save script — check root access")
                lines += "Saved ${scripts.first().fileName} → ${deploy.tmpPath}"
                deploy.tmpPath
            } else {
                val combined = buildString {
                    scripts.forEach { s ->
                        appendLine("// ── ${s.fileName} ──")
                        appendLine(ScriptUtils.readScriptContent(context, s))
                        appendLine()
                    }
                }
                val tmpFile = File(context.filesDir, "fridagate_combined.js")
                tmpFile.writeText(combined)
                val dest = "/data/local/tmp/fridagate_combined.js"
                RootUtils.executeSuCommand("cp ${tmpFile.absolutePath} $dest")
                RootUtils.executeSuCommand("chmod 644 $dest")
                tmpFile.delete()
                scripts.forEach { lines += "Saved ${it.fileName}" }
                lines += "Combined into fridagate_combined.js"
                dest
            }

            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(600)
            lines += "Stopped $packageName"

            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            recordInjectedPackage(packageName)
            val injectCmd = "nohup $INJECT_BINARY_PATH -f $packageName -s $scriptPath </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
            val launchResult = RootUtils.executeSuCommand(injectCmd).trim()
            if (!launchResult.contains("INJECT_LAUNCHED")) {
                lines += "ERROR: Could not start frida-inject as root."
                if (launchResult.isNotBlank()) lines += launchResult
                return@withContext lines
            }

            Thread.sleep(4000)
            val injectLog = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()

            if (injectLog.isNotEmpty()) {
                lines += "frida-inject output:"
                injectLog.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
            }

            val pid = findProcessId(packageName)
            val bootConfirmed = injectLog.contains("[FRIDAGATE-SCRIPT-BOOT]")
            if (pid != null) {
                if (bootConfirmed) {
                    lines +=
                        "✅ Confirmación de ejecución: se observó el marcador inicial del script dentro del proceso."
                    lines += "ℹ️ Esto confirma el inicio del script, no el éxito de todas sus operaciones posteriores."
                } else {
                    lines +=
                        "⚠️ $packageName está activo (PID $pid), pero esta ejecución no emitió el marcador de inicio; la inyección no queda confirmada."
                }
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, scriptPath)
            }
        } catch (e: Exception) {
            lines += "ERROR: ${e.message}"
        }

        lines
    }

    suspend fun launchWithCombinedScripts(
        context: Context,
        bypassScripts: List<ScriptUtils.BypassScript>,
        customScripts: List<com.hackpuntes.fridagate.data.models.FridaScript>,
        packageName: String
    ): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            return@withContext listOf("ERROR: Invalid target package name")
        }
        if (bypassScripts.isEmpty() && customScripts.isEmpty()) {
            return@withContext listOf("WARNING: No scripts are enabled")
        }
        injectionPrerequisiteError()?.let { return@withContext listOf(it) }

        try {
            lines += "⏹️ Cerrando $packageName antes de inyectar los scripts..."
            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(350)
            val remainingTargetPid = RootUtils.executeSuCommand("pidof $packageName").trim()
            if (remainingTargetPid.isNotBlank()) {
                RootUtils.executeSuCommand(
                    "kill -9 $remainingTargetPid 2>/dev/null || true; am force-stop $packageName"
                )
                Thread.sleep(250)
            }
            lines += "✓ Aplicación cerrada antes de iniciar frida-inject: $packageName"

            val source = buildString {
                bypassScripts.forEach { script ->
                    appendLine("// ===== ${script.name} =====")
                    appendLine("console.log('[FRIDAGATE-SCRIPT-BOOT]');")
                    appendLine(ScriptUtils.readScriptContent(context, script))
                    appendLine()
                }
                customScripts.forEach { script ->
                    appendLine("// ===== ${script.name} =====")
                    val normalizedScriptName = script.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val isUnityDiagnostics =
                        normalizedScriptName == "fridagateunityil2cppdiagnostics" ||
                            normalizedScriptName == "fridagateunityil2cppbundle"
                    val isRuntimeDiagnostics =
                        normalizedScriptName == "fridagateruntimediagnostics" ||
                            normalizedScriptName == "fridagateruntimediagnosticss"
                    val bundledDiagnostics = when {
                        isUnityDiagnostics -> runCatching {
                            context.assets.open("scripts/FridaGate_Unity_IL2CPP_Diagnostics.bundle.js")
                                .bufferedReader().use { it.readText() }
                        }.getOrNull()

                        isRuntimeDiagnostics -> runCatching {
                            context.assets.open("scripts/FridaGate_Runtime_Diagnostics.bundle.js")
                                .bufferedReader().use { it.readText() }
                        }.getOrNull()

                        else -> null
                    }
                    appendLine("console.log('[FRIDAGATE-SCRIPT-BOOT]');")
                    appendLine(bundledDiagnostics ?: script.code)
                    appendLine()
                    if ((isUnityDiagnostics || isRuntimeDiagnostics) && bundledDiagnostics == null) {
                        lines +=
                            "⚠️ No se encontró el bundle de diagnóstico esperado; se usó el código guardado del script."
                    } else if (bundledDiagnostics != null && isUnityDiagnostics) {
                        lines += "✓ Bundle Unity IL2CPP cargado con bridges Java/IL2CPP."
                    } else if (bundledDiagnostics != null && isRuntimeDiagnostics) {
                        lines += "✓ Bundle Runtime Diagnostics cargado con frida-java-bridge integrado."
                    }
                }
            }
            val localFile = File(context.filesDir, "fridagate_enabled_scripts.js")
            localFile.writeText(source)
            val devicePath = "/data/local/tmp/fridagate_enabled_scripts.js"
            val copyResult = RootUtils.executeSuCommand(
                "cp \"${localFile.absolutePath}\" $devicePath && chmod 644 $devicePath && test -s $devicePath && echo SCRIPT_STAGE_OK"
            )
            localFile.delete()
            if (!copyResult.contains("SCRIPT_STAGE_OK") ||
                copyResult.contains("Permission denied", ignoreCase = true) ||
                copyResult.contains("No such file", ignoreCase = true)
            ) {
                return@withContext listOf("ERROR: Could not stage scripts at $devicePath: $copyResult")
            }

            val installedInjectVersion = getInstalledVersion() ?: "desconocida"
            val installedServerVersion = FridaUtils.getInstalledFridaVersion() ?: "desconocida"
            lines +=
                "ℹ️ Versiones detectadas: frida-server=$installedServerVersion; frida-inject=$installedInjectVersion"
            if (installedServerVersion != installedInjectVersion) {
                lines +=
                    "⚠️ Las versiones de frida-server y frida-inject no coinciden; actualiza ambos a la misma versión antes de seguir."
            }

            lines += "Enabled scripts: ${bypassScripts.size + customScripts.size}"
            bypassScripts.forEach { lines += "✓ ${it.name}" }
            customScripts.forEach { lines += "✓ ${it.name}" }
            lines += "Combined script staged at $devicePath"
            if (Regex("(?m)^\\s*import\\s+").containsMatchIn(source)) {
                lines +=
                    "⚠️ El script contiene importaciones ES (por ejemplo, frida-il2cpp-bridge). FridaGate no empaqueta módulos npm automáticamente; deben compilarse en un único bundle JavaScript antes de ejecutarlos."
            }

            val runtimeDiagnosticsOnly =
                bypassScripts.isEmpty() &&
                    customScripts.size == 1 &&
                    customScripts.any { script ->
                        val normalized = script.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                        normalized == "fridagateruntimediagnostics" ||
                            normalized == "fridagateruntimediagnosticsjs" ||
                            normalized == "fridagateruntimediagnosticss"
                    }

            if (runtimeDiagnosticsOnly) {
                lines += "ℹ️ Diagnóstico Runtime: iniciando la aplicación normalmente antes de adjuntar Frida."
                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)

                val normalLaunch = RootUtils.executeSuCommand("monkey -p $packageName 1 2>&1").trim()
                if (normalLaunch.isNotBlank()) {
                    normalLaunch.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
                }

                var targetPid = ""
                repeat(20) {
                    targetPid = RootUtils.executeSuCommand("pidof $packageName")
                        .trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (targetPid.isBlank()) Thread.sleep(500)
                }
                if (!targetPid.matches(Regex("\\d+"))) {
                    lines += "❌ La aplicación no llegó a iniciar normalmente; no se inyectó el diagnóstico."
                    return@withContext lines
                }

                Thread.sleep(2000)
                lines += "✓ Aplicación iniciada normalmente (PID $targetPid). Adjuntando diagnóstico..."

                val attachCmd = "nohup $INJECT_BINARY_PATH -p $targetPid -s $devicePath -e </dev/null > $INJECT_LOG 2>&1 & echo \$! > /data/local/tmp/fridagate_inject.pid; chmod 600 /data/local/tmp/fridagate_inject.pid; echo INJECT_LAUNCHED"
                val attachResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!attachResult.contains("INJECT_LAUNCHED")) {
                    lines += "❌ No se pudo iniciar frida-inject en modo attach."
                    if (attachResult.isNotBlank()) lines += attachResult
                    return@withContext lines
                }

                var attachLog = ""
                var lastAttachLog = ""
                repeat(10) {
                    Thread.sleep(1000)
                    attachLog = RootUtils.executeSuCommand("cat $INJECT_LOG 2>/dev/null").trim()
                    if (attachLog.isNotBlank() && attachLog != lastAttachLog) {
                        val newText = attachLog.removePrefix(lastAttachLog).trim()
                        if (lastAttachLog.isBlank()) lines += "frida-inject output:"
                        newText.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
                        lastAttachLog = attachLog
                    }
                }

                val stillRunning = findProcessId(packageName)
                if (stillRunning != null) {
                    if (attachLog.contains("[FRIDAGATE-SCRIPT-BOOT]")) {
                        lines += "✅ Diagnóstico adjuntado; la aplicación continúa activa (PID $stillRunning)."
                    } else {
                        lines +=
                            "⚠️ La aplicación continúa activa (PID $stillRunning), pero no se confirmó el inicio del script."
                        lines += "ℹ️ Revisa el mensaje de frida-inject mostrado arriba."
                    }
                } else {
                    lines += "❌ La aplicación terminó después del intento de attach."
                    val crashDiagnostics = collectTargetCrashDiagnostics(packageName)
                    if (crashDiagnostics.isNotBlank()) {
                        crashDiagnostics.lines().forEach { lines += "📱 $it" }
                    }
                }
                return@withContext lines
            }

            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            recordInjectedPackage(packageName)
            val injectCmd = "nohup $INJECT_BINARY_PATH -f $packageName -s $devicePath </dev/null > $INJECT_LOG 2>&1 & echo \$! > /data/local/tmp/fridagate_inject.pid; chmod 600 /data/local/tmp/fridagate_inject.pid; echo INJECT_LAUNCHED"
            val launchResult = RootUtils.executeSuCommand(injectCmd).trim()
            if (!launchResult.contains("INJECT_LAUNCHED")) {
                lines += "ERROR: Could not start frida-inject as root."
                if (launchResult.isNotBlank()) lines += launchResult
                return@withContext lines
            }
            lines += "Launching $packageName with enabled scripts..."

            var injectLog = ""
            var lastPrintedLog = ""
            repeat(12) {
                Thread.sleep(1000)
                injectLog = RootUtils.executeSuCommand("cat $INJECT_LOG 2>/dev/null").trim()
                if (injectLog.isNotBlank() && injectLog != lastPrintedLog) {
                    val newLines = injectLog.removePrefix(lastPrintedLog).trim()
                    if (newLines.isNotBlank()) {
                        if (lastPrintedLog.isBlank()) lines += "frida-inject output:"
                        newLines.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
                        lastPrintedLog = injectLog
                    }
                }
                if (injectLog.contains("[FG-UNITY-IL2CPP]") || injectLog.contains("Error", ignoreCase = true)) {
                    if (it >= 4) return@repeat
                }
            }

            if (injectLog.isBlank()) {
                val injectorState = RootUtils.executeSuCommand(
                    "if [ -f /data/local/tmp/fridagate_inject.pid ]; then p=\$(cat /data/local/tmp/fridagate_inject.pid); " +
                        "if kill -0 \$p 2>/dev/null; then echo INJECTOR_ALIVE_PID=\$p; else echo INJECTOR_NOT_RUNNING; fi; fi"
                ).trim()
                val serverState = if (FridaUtils.isFridaServerRunning()) "frida-server sigue activo" else "frida-server NO está activo"
                lines += "⚠️ No se recibió salida de frida-inject tras 12 s. Estado: $injectorState; $serverState."
                lines += "ℹ️ El registro detallado se volverá a consultar al detener la inyección."
            }

            val bootMarker = "[FRIDAGATE-SCRIPT-BOOT]"
            val expectedScriptBoots = bypassScripts.size + customScripts.size
            val observedScriptBoots = injectLog.split(bootMarker).size - 1
            val scriptBootConfirmed = observedScriptBoots >= expectedScriptBoots
            val pid = findProcessId(packageName)
            if (pid != null) {
                if (scriptBootConfirmed) {
                    lines +=
                        "✅ Confirmación de ejecución: $observedScriptBoots/$expectedScriptBoots scripts alcanzaron su marcador inicial dentro de Frida."
                    lines +=
                        "ℹ️ Esto confirma que el agente ejecutó el inicio de cada script; no garantiza que todas sus operaciones posteriores hayan terminado sin errores."
                } else {
                    lines +=
                        "⚠️ Proceso $packageName activo (PID $pid), pero la ejecución completa no está confirmada: marcadores $observedScriptBoots/$expectedScriptBoots."
                    lines += "ℹ️ Revisa la salida de frida-inject para detectar errores de sintaxis, carga o ejecución."
                }
            } else if (observedScriptBoots > 0) {
                lines += "⚠️ El script alcanzó su marcador inicial, pero el proceso $packageName ya no está activo."
                lines +=
                    "ℹ️ Se omite el relanzamiento automático para evitar abrir la aplicación por segunda vez. Revisa la salida de frida-inject."
                lines += "🔎 Recopilando errores recientes de Android para identificar por qué terminó el proceso..."
                val crashDiagnostics = collectTargetCrashDiagnostics(packageName)
                if (crashDiagnostics.isNotBlank()) {
                    crashDiagnostics.lines().forEach { lines += "📱 $it" }
                } else {
                    lines += "ℹ️ Logcat no mostró una excepción/crash reconocible en el buffer reciente."
                    lines +=
                        "ℹ️ Si vuelve a ocurrir, comparte este registro completo y el resultado de logcat del sistema."
                }
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, devicePath)
            }
        } catch (e: Exception) {
            lines += "ERROR injecting enabled scripts: ${e.message}"
        }
        lines
    }

    suspend fun launchWithCustomScript(
        context: Context,
        scriptName: String,
        scriptCode: String,
        packageName: String
    ): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        if (scriptCode.isBlank()) return@withContext listOf("ERROR: Script is empty")
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            return@withContext listOf("ERROR: Invalid target package name")
        }
        injectionPrerequisiteError()?.let { return@withContext listOf(it) }

        try {
            val localFile = File(context.filesDir, "fridagate_custom.js")
            localFile.writeText(scriptCode)
            val devicePath = "/data/local/tmp/fridagate_custom.js"
            val copyResult = RootUtils.executeSuCommand(
                "cp \"${localFile.absolutePath}\" $devicePath && chmod 644 $devicePath"
            )
            localFile.delete()
            if (copyResult.contains("Permission denied", ignoreCase = true) ||
                copyResult.contains("No such file", ignoreCase = true)
            ) {
                return@withContext listOf("ERROR: Could not stage custom script: $copyResult")
            }

            lines += "Staged $scriptName → $devicePath"
            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(600)
            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            recordInjectedPackage(packageName)
            val injectCmd = "nohup $INJECT_BINARY_PATH -f $packageName -s $devicePath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
            val launchResult = RootUtils.executeSuCommand(injectCmd).trim()
            if (!launchResult.contains("INJECT_LAUNCHED")) {
                lines += "ERROR: Could not start frida-inject as root."
                if (launchResult.isNotBlank()) lines += launchResult
                return@withContext lines
            }
            lines += "Launching $scriptName in $packageName..."
            Thread.sleep(4000)

            val injectLog = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
            if (injectLog.isNotEmpty()) {
                lines += "frida-inject output:"
                injectLog.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
            }

            val pid = findProcessId(packageName)
            val bootConfirmed = injectLog.contains("[FRIDAGATE-SCRIPT-BOOT]")
            if (pid != null) {
                if (bootConfirmed) {
                    lines +=
                        "✅ Confirmación de ejecución: se observó el marcador inicial del script dentro del proceso."
                    lines += "ℹ️ Esto confirma el inicio del script, no el éxito de todas sus operaciones posteriores."
                } else {
                    lines +=
                        "⚠️ $packageName está activo (PID $pid), pero esta ejecución no emitió el marcador de inicio; la inyección no queda confirmada."
                }
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, devicePath)
            }
        } catch (e: Exception) {
            lines += "ERROR injecting custom script: ${e.message}"
        }
        lines
    }

    suspend fun stopTargetApp(packageName: String): List<String> = withContext(Dispatchers.IO) {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            return@withContext listOf("❌ Nombre de paquete inválido; no se detuvo ninguna aplicación.")
        }

        val lines = mutableListOf<String>()
        try {
            lines += "⏹️ Deteniendo scripts e inyección para $packageName…"

            val finalInjectLog = RootUtils.executeSuCommand("cat $INJECT_LOG 2>/dev/null").trim()
            if (finalInjectLog.isNotBlank()) {
                lines += "📋 Salida final de frida-inject (ejecución que se está deteniendo):"
                finalInjectLog.lines().takeLast(100).filter { it.isNotBlank() }.forEach { lines += "  $it" }
            } else {
                lines += "⚠️ El archivo de salida de frida-inject sigue vacío."
            }
            lines += if (FridaUtils.isFridaServerRunning()) {
                "ℹ️ Estado al detener: frida-server está activo."
            } else {
                "⚠️ Estado al detener: frida-server no está activo."
            }

            val pid = RootUtils.executeSuCommand("cat /data/local/tmp/fridagate_inject.pid 2>/dev/null").trim()
            if (pid.matches(Regex("\\d+"))) {
                RootUtils.executeSuCommand(
                    "kill -TERM $pid 2>/dev/null || true; sleep 1; kill -KILL $pid 2>/dev/null || true"
                )
                lines += "✓ Proceso frida-inject detenido (PID $pid)."
            }
            RootUtils.executeSuCommand(
                "pkill -f '[f]ridagate_enabled_scripts.js' 2>/dev/null || true; rm -f /data/local/tmp/fridagate_inject.pid"
            )

            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(350)
            val remaining = RootUtils.executeSuCommand("pidof $packageName").trim()
            if (remaining.isBlank()) {
                lines += "✓ Aplicación detenida por root: $packageName"
            } else {
                RootUtils.executeSuCommand("kill -9 $remaining 2>/dev/null || true")
                RootUtils.executeSuCommand("am force-stop $packageName")
                lines += "✓ Solicitud de cierre forzado enviada a $packageName"
            }

            val recents = RootUtils.executeSuCommand("dumpsys activity recents")
            val recentLines = recents.lines()
            val taskIds = linkedSetOf<String>()
            recentLines.forEachIndexed { index, line ->
                if (line.contains(packageName)) {
                    val from = (index - 20).coerceAtLeast(0)
                    val taskLine = recentLines.subList(from, index + 1)
                        .lastOrNull { it.contains("Recent #") && it.contains("Task{") }
                    val id = taskLine?.let { Regex("Task\\{.*?#(\\d+)\\b").find(it)?.groupValues?.get(1) }
                    if (id != null) taskIds += id
                }
            }

            var removed = false
            for (taskId in taskIds) {
                var output = RootUtils.executeSuCommand("am task remove $taskId 2>&1")
                if (output.contains("Unknown command", ignoreCase = true) ||
                    output.contains("Error", ignoreCase = true) ||
                    output.contains("Exception", ignoreCase = true)
                ) {
                    output = RootUtils.executeSuCommand("cmd activity task remove $taskId 2>&1")
                }
                if (!output.contains("Unknown command", ignoreCase = true) &&
                    !output.contains("Error", ignoreCase = true) &&
                    !output.contains("Exception", ignoreCase = true) &&
                    !output.contains("not found", ignoreCase = true)
                ) {
                    removed = true
                    lines += "✓ Se solicitó quitar la tarea $taskId de Aplicaciones recientes."
                }
            }
            if (!removed) {
                lines +=
                    "ℹ️ No se pudo confirmar la eliminación de Recientes en esta versión de Android; la aplicación sí recibió el cierre forzado."
            }
            RootUtils.executeSuCommand("rm -f /data/local/tmp/fridagate_enabled_scripts.js")
            lines += "✓ Finalizado. Los registros de FridaGate se conservaron."
        } catch (e: Exception) {
            lines += "❌ Error al detener $packageName: ${e.message}"
        }
        lines
    }

    private suspend fun recordInjectedPackage(packageName: String) {
        if (packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            RootUtils.executeSuCommand(
                "touch $INJECTED_PACKAGES_FILE; grep -Fx '$packageName' $INJECTED_PACKAGES_FILE >/dev/null 2>&1 || echo '$packageName' >> $INJECTED_PACKAGES_FILE"
            )
        }
    }

    suspend fun stopAllInjections(): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        try {
            val packages = RootUtils.executeSuCommand("cat $INJECTED_PACKAGES_FILE 2>/dev/null")
                .lineSequence().map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z0-9._]+")) }.distinct().toList()
            packages.forEach { pkg ->
                RootUtils.executeSuCommand("am force-stop $pkg")
                lines += "✓ Scripts detenidos y aplicación cerrada: $pkg"
            }
            RootUtils.executeSuCommand("pkill -9 -f '[f]rida-inject' 2>/dev/null || true")
            RootUtils.executeSuCommand(
                "for maps in /proc/[0-9]*/maps; do " +
                    "if grep -qi frida-agent \"\$maps\" 2>/dev/null || grep -qi frida-gadget \"\$maps\" 2>/dev/null; then " +
                    "pid=\$(echo \"\$maps\" | cut -d/ -f3); kill -9 \"\$pid\" 2>/dev/null || true; fi; done"
            )
            RootUtils.executeSuCommand(
                "rm -f /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE " +
                    "/data/local/tmp/fridagate_enabled_scripts.js /data/local/tmp/fridagate_combined.js " +
                    "/data/local/tmp/fridagate_custom.js $INJECT_LOG"
            )
            lines += "✓ Procesos de inyección y scripts temporales de FridaGate limpiados."
            if (packages.isEmpty()) lines += "ℹ️ No había aplicaciones registradas; no se cerraron aplicaciones ajenas."
        } catch (_: Exception) {
            lines += "❌ Error al detener inyecciones."
        }
        lines
    }

    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand(
                "rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE"
            )
            !isFridaInjectInstalled()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) {
                        append(
                            "⚠️ Found target process $packageName (PID $pid), but PID presence does NOT confirm successful injection or script execution."
                        )
                    } else {
                        append("Could not find process — is frida-server running?")
                    }
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    private suspend fun collectTargetCrashDiagnostics(packageName: String): String = withContext(Dispatchers.IO) {
        try {
            val pattern = "FATAL EXCEPTION|AndroidRuntime|Fatal signal|SIGSEGV|SIGABRT|" +
                "tombstoned|crash_dump|am_crash|am_anr|has died|Killing .*$packageName|$packageName"
            RootUtils.executeSuCommand(
                "logcat -d -t 800 2>/dev/null | grep -E -i -B 4 -A 18 '$pattern' | tail -n 100"
            ).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun findProcessId(packageName: String): String? {
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}