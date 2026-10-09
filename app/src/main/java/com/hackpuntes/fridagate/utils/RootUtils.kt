package com.hackpuntes.fridagate.utils

/**
 * Executes shell commands through the device's superuser manager.
 *
 * Each command gets its own `su -c` process. This avoids the old persistent-shell
 * implementation, which read output using InputStream.available() and could return
 * stale, truncated, or NUL-padded output before a command had actually completed.
 */
object RootUtils {

    /**
     * Checks whether the superuser manager grants this app root access.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Runs one command as root and returns all merged stdout/stderr output.
     * Empty output is returned if su cannot be started or the command fails before
     * producing output; callers should verify state when command success matters.
     */
    fun executeSuCommand(command: String): String {
        return try {
            if (!isRootAvailable()) return ""
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Kept for compatibility with existing callers. Commands no longer share a
     * persistent shell, so there is no long-lived process to close.
     */
    fun closeSuProcess() {
        // No persistent su process is kept.
    }
}
