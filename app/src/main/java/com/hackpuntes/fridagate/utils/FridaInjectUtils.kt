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

    // -------------------------------------------------------------------------
    // Status checks
    // -------------------------------------------------------------------------

    suspend fun isFridaInjectInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = RootUtils.executeSuCommand("ls $INJECT_BINARY_PATH")
            r.contains("frida-inject") && !r.contains("No such file")
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
            // frida-inject --eternalize: keep the script alive even after frida-inject exits
            val injectCmd = "$INJECT_BINARY_PATH -f $packageName -s $scriptPath -e > $INJECT_LOG 2>&1 &"
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", injectCmd))
                // Intentionally no waitFor() — frida-inject stays attached to the target process
            } catch (e: Exception) {
                lines += "ERROR launching frida-inject: ${e.message}"
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
                "cp \"${localFile.absolutePath}\" $devicePath && chmod 644 $devicePath"
            )
            localFile.delete()
            if (copyResult.contains("Permission denied", ignoreCase = true) ||
                copyResult.contains("No such file", ignoreCase = true)) {
                return@withContext listOf("ERROR: Could not stage scripts: $copyResult")
            }

            lines += "Enabled scripts: ${bypassScripts.size + customScripts.size}"
            bypassScripts.forEach { lines += "✓ ${it.name}" }
            customScripts.forEach { lines += "✓ ${it.name}" }
            lines += "Combined script staged at $devicePath"

            RootUtils.executeSuCommand("am force-stop $packageName")
            Thread.sleep(600)
            RootUtils.executeSuCommand("rm -f $INJECT_LOG")

            val injectCmd = "$INJECT_BINARY_PATH -f $packageName -s $devicePath -e > $INJECT_LOG 2>&1 &"
            Runtime.getRuntime().exec(arrayOf("su", "-c", injectCmd))
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

            val injectCmd = "$INJECT_BINARY_PATH -f $packageName -s $devicePath -e > $INJECT_LOG 2>&1 &"
            Runtime.getRuntime().exec(arrayOf("su", "-c", injectCmd))
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
                val attachCmd = "$INJECT_BINARY_PATH -n $packageName -s $scriptPath -e > $INJECT_LOG 2>&1 &"
                Runtime.getRuntime().exec(arrayOf("su", "-c", attachCmd))
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
