package com.hackpuntes.fridagate.utils

/**
 * Executes shell commands through the device's superuser manager.
 *
 * Each command gets its own `su -c` process. Output is drained on a separate
 * thread and the wait is bounded by a timeout, so a command (or a background
 * descendant that inherited the pipe) can never block the caller forever.
 */
object RootUtils {

    // Cached after the first successful check to avoid spawning an extra `su`
    // for every command (polling loops used to spawn two su processes per call).
    @Volatile
    private var rootGranted = false

    /**
     * Checks whether the superuser manager grants this app root access.
     */
    fun isRootAvailable(): Boolean {
        if (rootGranted) return true
        val output = runSu("id", 15) ?: return false
        rootGranted = output.contains("uid=0")
        return rootGranted
    }

    /**
     * Runs one command as root and returns merged stdout/stderr output.
     * If the timeout is reached, the su process is killed and whatever output
     * was produced so far is returned.
     */
    fun executeSuCommand(command: String, timeoutSeconds: Long = 30): String {
        if (!isRootAvailable()) return ""
        return runSu(command, timeoutSeconds) ?: ""
    }

    private fun runSu(command: String, timeoutSeconds: Long): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            try { process.outputStream.close() } catch (_: Exception) {}

            val buffer = StringBuffer()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val chunk = CharArray(4096)
                        while (true) {
                            val n = r.read(chunk)
                            if (n < 0) break
                            buffer.append(chunk, 0, n)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            // minSdk 24: Process.waitFor(timeout) and destroyForcibly() need API 26,
            // so poll exitValue() against a deadline instead.
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            var finished = false
            while (!finished && System.currentTimeMillis() < deadline) {
                try {
                    process.exitValue()
                    finished = true
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!finished) process.destroy()

            // If a background child kept the pipe open, don't wait for EOF forever.
            reader.join(1500)
            if (reader.isAlive) {
                try { process.inputStream.close() } catch (_: Exception) {}
            }
            buffer.toString()
        } catch (_: Exception) {
            null
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