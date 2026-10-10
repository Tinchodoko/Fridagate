package com.hackpuntes.fridagate.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FridaInjectUtils - Manages the frida-inject binary and on-device script injection.
 *
 * frida-inject is a compiled C binary from the Frida project that runs directly
 * on Android — no Python, no connected PC required.
 *
 * It connects to the local frida-server (must be running at 127.0.0.1:27042)
 * and asks it to spawn or attach to the target app.
 *
 * Download URL pattern:
 *   https://github.com/frida/frida/releases/download/<ver>/frida-inject-<ver>-android-<arch>.xz
 */
object FridaInjectUtils {

    const val INJECT_BINARY_PATH = "/data/local/tmp/frida-inject"
    private const val INJECT_VERSION_FILE = "/data/local/tmp/frida-inject-version.txt"

    // frida-inject writes its output here so we can read it back for logging
    private const val INJECT_LOG = "/data/local/tmp/fridagate_inject.log"
    private const val INJECTED_PACKAGES_FILE = "/data/local/tmp/fridagate_injected_packages.txt"

    // -------------------------------------------------------------------------
    // Status checks
    // -------------------------------------------------------------------------

    suspend fun isFridaInjectInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("test -x $INJECT_BINARY_PATH && echo FRIDA_INJECT_EXECUTABLE")
                .contains("FRIDA_INJECT_EXECUTABLE")
        } catch (e: Exception) { false }
    }

    suspend fun getInstalledVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val check = RootUtils.executeSuCommand("ls $INJECT_VERSION_FILE")
            if (check.contains("No such file")) return@withContext null
            RootUtils.executeSuCommand("cat $INJECT_VERSION_FILE").trim().ifEmpty { null }
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // Download and installation
    // -------------------------------------------------------------------------

    suspend fun downloadAndInstall(context: Context, version: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val arch = FridaUtils.getDeviceArchitecture()
                val url  = "https://github.com/frida/frida/releases/download/$version/" +
                           "frida-inject-$version-android-$arch.xz"

                // Reuse FridaUtils download — it decompresses .xz and saves as filesDir/frida-server
                val downloaded = FridaUtils.downloadFridaServerFromUrl(context, url)
                    ?: return@withContext false

                val injectFile = File(context.filesDir, "frida-inject")
                downloaded.renameTo(injectFile)
                injectFile.setExecutable(true)

                RootUtils.executeSuCommand("cp ${injectFile.absolutePath} $INJECT_BINARY_PATH")
                RootUtils.executeSuCommand("chmod 755 $INJECT_BINARY_PATH")
                RootUtils.executeSuCommand("echo '$version' > $INJECT_VERSION_FILE")

                try { injectFile.delete() } catch (_: Exception) {}
                isFridaInjectInstalled()
            } catch (e: Exception) { false }
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

    /**
     * Refuse to report an injection attempt as successful when its prerequisites
     * are missing. Root alone is not enough: both frida-inject and frida-server
     * must be available on the device.
     */
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

    /**
     * Spawns the target app with one or more scripts injected from the first instruction.
     *
     * Flow:
     *  1. Save all scripts to /data/local/tmp/
     *  2. If more than one script: concatenate them into a single temp file
     *     (frida-inject takes exactly one script argument)
     *  3. Kill any existing instance of the target app
     *  4. Run: frida-inject -f <package> <script> and capture real output
     *  5. Read the log to report success/error
     *
     * @param context     Android context (to read scripts from assets)
     * @param scripts     Enabled bypass scripts (1 or more)
     * @param packageName Target app package
     * @return List of log lines to show in the UI
     */
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
            // Step 1: save scripts and build the path to pass to frida-inject
            val scriptPath = if (scripts.size == 1) {
                val deploy = ScriptUtils.saveScriptToDevice(context, scripts.first())
                    ?: return@withContext listOf("ERROR: Could not save script — check root access")
                lines += "Saved ${scripts.first().fileName} → ${deploy.tmpPath}"
                deploy.tmpPath
            } else {
                // Multiple scripts: concatenate into one temp file
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

            // Step 2: kill existing instance
            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(600)
            lines += "Stopped $packageName"

            // Step 3: clear previous log
            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            // Step 4: spawn with frida-inject in its own independent su process.
            //
            // WHY NOT RootUtils.executeSuCommand():
            //   RootUtils keeps a persistent su shell. Backgrounding frida-inject (&) inside
            //   that shell makes it a child job — it can receive SIGHUP when the shell resets
            //   state, and job-control semantics vary by Android shell implementation.
            //
            // WHY Runtime.exec() directly:
            //   Creates a brand-new su process whose sole child is frida-inject.
            //   No persistent shell in between, no job-control issues.
            //   We don't call waitFor() so it runs for the lifetime of the target app.
            //
            recordInjectedPackage(packageName)
            // frida-inject --eternalize: keep the script alive even after frida-inject exits
            val injectCmd = "nohup $INJECT_BINARY_PATH -f $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
            val launchResult = RootUtils.executeSuCommand(injectCmd).trim()
            if (!launchResult.contains("INJECT_LAUNCHED")) {
                lines += "ERROR: Could not start frida-inject as root."
                if (launchResult.isNotBlank()) lines += launchResult
                return@withContext lines
            }

            // Step 5: wait for frida-inject to spawn and inject, then read output
            Thread.sleep(4000)
            val injectLog = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()

            if (injectLog.isNotEmpty()) {
                lines += "frida-inject output:"
                injectLog.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
            }

            // Step 6: verify the app is running
            val pid = findProcessId(packageName)
            if (pid != null) {
                lines += "✓ $packageName is running (PID $pid)"
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, scriptPath)
            }

        } catch (e: Exception) {
            lines += "ERROR: ${e.message}"
        }

        lines
    }

    /**
     * Launches an app once with every enabled built-in and user-authored script.
     * All source files are combined into one staged JS file because frida-inject
     * accepts one script path per invocation.
     */
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
            val source = buildString {
                bypassScripts.forEach { script ->
                    appendLine("// ===== ${script.name} =====")
                    appendLine(ScriptUtils.readScriptContent(context, script))
                    appendLine()
                }
                customScripts.forEach { script ->
                    appendLine("// ===== ${script.name} =====")
                    appendLine(script.code)
                    appendLine()
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
                copyResult.contains("No such file", ignoreCase = true)) {
                return@withContext listOf("ERROR: Could not stage scripts at $devicePath: $copyResult")
            }

            lines += "Enabled scripts: ${bypassScripts.size + customScripts.size}"
            bypassScripts.forEach { lines += "✓ ${it.name}" }
            customScripts.forEach { lines += "✓ ${it.name}" }
            lines += "Combined script staged at $devicePath"

            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(600)
            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            recordInjectedPackage(packageName)
            val injectCmd = "nohup $INJECT_BINARY_PATH -f $packageName -s $devicePath -e </dev/null > $INJECT_LOG 2>&1 & echo \$! > /data/local/tmp/fridagate_inject.pid; chmod 600 /data/local/tmp/fridagate_inject.pid; echo INJECT_LAUNCHED"
            val launchResult = RootUtils.executeSuCommand(injectCmd).trim()
            if (!launchResult.contains("INJECT_LAUNCHED")) {
                lines += "ERROR: Could not start frida-inject as root."
                if (launchResult.isNotBlank()) lines += launchResult
                return@withContext lines
            }
            lines += "Launching $packageName with enabled scripts..."
            Thread.sleep(4000)

            val injectLog = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
            if (injectLog.isNotEmpty()) {
                lines += "frida-inject output:"
                injectLog.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
            }

            val pid = findProcessId(packageName)
            if (pid != null) {
                lines += "✓ $packageName is running (PID $pid)"
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, devicePath)
            }
        } catch (e: Exception) {
            lines += "ERROR injecting enabled scripts: ${e.message}"
        }
        lines
    }

    /**
     * Injects a user-authored JavaScript file into a selected app using frida-inject.
     * The script is copied to the root-readable staging directory before injection.
     */
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
                copyResult.contains("No such file", ignoreCase = true)) {
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
            if (pid != null) {
                lines += "✓ $packageName is running (PID $pid)"
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, devicePath)
            }
        } catch (e: Exception) {
            lines += "ERROR injecting custom script: ${e.message}"
        }
        lines
    }

    /**
     * Stops the target app and the injector launched by the combined-script flow.
     * Also attempts to remove that app's task from Android Recents; OEM/Android
     * versions may restrict task removal, so that final step is best-effort.
     */
    suspend fun stopTargetApp(packageName: String): List<String> = withContext(Dispatchers.IO) {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            return@withContext listOf("❌ Nombre de paquete inválido; no se detuvo ninguna aplicación.")
        }

        val lines = mutableListOf<String>()
        try {
            lines += "⏹️ Deteniendo scripts e inyección para $packageName…"

            val pid = RootUtils.executeSuCommand("cat /data/local/tmp/fridagate_inject.pid 2>/dev/null").trim()
            if (pid.matches(Regex("\\d+"))) {
                RootUtils.executeSuCommand("kill -TERM $pid 2>/dev/null || true; sleep 1; kill -KILL $pid 2>/dev/null || true")
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

            // Try to find the exact task belonging to this package in Recents.
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
                    output.contains("Exception", ignoreCase = true)) {
                    output = RootUtils.executeSuCommand("cmd activity task remove $taskId 2>&1")
                }
                if (!output.contains("Unknown command", ignoreCase = true) &&
                    !output.contains("Error", ignoreCase = true) &&
                    !output.contains("Exception", ignoreCase = true) &&
                    !output.contains("not found", ignoreCase = true)) {
                    removed = true
                    lines += "✓ Se solicitó quitar la tarea $taskId de Aplicaciones recientes."
                }
            }
            if (!removed) {
                lines += "ℹ️ No se pudo confirmar la eliminación de Recientes en esta versión de Android; la aplicación sí recibió el cierre forzado."
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

    /** Stop tracked eternalized injections without force-stopping unrelated apps. */
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
            RootUtils.executeSuCommand(
                """
                pkill -9 -f '[f]rida-inject' 2>/dev/null || true
                # Catch eternalized injections created by older versions that did not track target packages.
                for maps in /proc/[0-9]*/maps; do
                    if grep -qiE 'frida-agent|frida-gadget' "${'
                    fi
                done
                """.trimIndent()
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

    /** Removes the frida-inject binary and its version metadata. */
    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand("rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE")
            !isFridaInjectInstalled()
        } catch (_: Exception) { false }
    }

    /** Stops a custom-script frida-inject process without touching the predefined bypass injection. */
    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        // Try pidof first (modern Android)
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        // Fall back to ps
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
}maps" 2>/dev/null; then
                        pid=${'
                    fi
                done
                """.trimIndent()
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

    /** Removes the frida-inject binary and its version metadata. */
    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand("rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE")
            !isFridaInjectInstalled()
        } catch (_: Exception) { false }
    }

    /** Stops a custom-script frida-inject process without touching the predefined bypass injection. */
    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        // Try pidof first (modern Android)
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        // Fall back to ps
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
}{maps#/proc/}
                        pid=${'
                    fi
                done
                """.trimIndent()
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

    /** Removes the frida-inject binary and its version metadata. */
    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand("rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE")
            !isFridaInjectInstalled()
        } catch (_: Exception) { false }
    }

    /** Stops a custom-script frida-inject process without touching the predefined bypass injection. */
    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        // Try pidof first (modern Android)
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        // Fall back to ps
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
}{pid%/maps}
                        kill -9 "${'
                    fi
                done
                """.trimIndent()
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

    /** Removes the frida-inject binary and its version metadata. */
    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand("rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE")
            !isFridaInjectInstalled()
        } catch (_: Exception) { false }
    }

    /** Stops a custom-script frida-inject process without touching the predefined bypass injection. */
    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        // Try pidof first (modern Android)
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        // Fall back to ps
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
}pid" 2>/dev/null || true
                    fi
                done
                """.trimIndent()
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

    /** Removes the frida-inject binary and its version metadata. */
    suspend fun uninstallFridaInject(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopAllInjections()
            RootUtils.executeSuCommand("rm -f $INJECT_BINARY_PATH $INJECT_VERSION_FILE $INJECT_LOG /data/local/tmp/fridagate_inject.pid $INJECTED_PACKAGES_FILE")
            !isFridaInjectInstalled()
        } catch (_: Exception) { false }
    }

    /** Stops a custom-script frida-inject process without touching the predefined bypass injection. */
    suspend fun stopCustomScript(): List<String> = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("pkill -f '[f]ridagate_custom.js'")
            listOf("Stop request sent for the custom script injection.")
        } catch (e: Exception) {
            listOf("ERROR stopping custom script: ${e.message}")
        }
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                )
                Thread.sleep(1000)

                RootUtils.executeSuCommand("rm -f $INJECT_LOG")
                recordInjectedPackage(packageName)
                val attachCmd = "nohup $INJECT_BINARY_PATH -n $packageName -s $scriptPath -e </dev/null > $INJECT_LOG 2>&1 & echo INJECT_LAUNCHED"
                val launchResult = RootUtils.executeSuCommand(attachCmd).trim()
                if (!launchResult.contains("INJECT_LAUNCHED")) {
                    return@withContext "ERROR: Could not start frida-inject attach process."
                }
                Thread.sleep(2500)

                val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        // Try pidof first (modern Android)
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()

        // Fall back to ps
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
