package com.hackpuntes.fridagate.core.frida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Constantes compartidas
// ─────────────────────────────────────────────────────────────────────────────

private const val FRIDA_INJECT_PATH = "/data/local/tmp/frida-inject"
private const val CONFIRM_MARKER = "FRIDAGATE_INJECTION_OK"
private const val CONFIRM_TIMEOUT_MS = 10_000L // 10 s para ver el marcador
private const val READ_POLL_MS = 50L // polling cuando no hay línea lista

// ─────────────────────────────────────────────────────────────────────────────
// FridaInjectionService  (inyección via shell + root, frida-inject)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lanza frida-inject como root, lee su stdout en tiempo real y busca el
 * marcador FRIDAGATE_INJECTION_OK para confirmar la inyección.
 * El proceso se mantiene vivo después de la confirmación (frida-inject debe
 * permanecer adjunto mientras la app objetivo corre).
 */
class FridaInjectionService(private val context: Context, private val logCallback: (String) -> Unit = {}) {

    /** Proceso activo de frida-inject. Null si no hay inyección en curso. */
    private var activeProcess: Process? = null

    // ── Inyección por PID ────────────────────────────────────────────────────

    suspend fun injectByPid(pid: Int, scriptPath: String): Boolean = injectInternal(
        target = "-p $pid",
        scriptPath = scriptPath
    )

    // ── Inyección por nombre de paquete ─────────────────────────────────────

    suspend fun injectByPackage(packageName: String, scriptPath: String): Boolean = injectInternal(
        target = "-n $packageName",
        scriptPath = scriptPath
    )

    // ── Detener inyección activa ─────────────────────────────────────────────

    fun stopInjection() {
        activeProcess?.destroy()
        activeProcess = null
        logCallback("⏹️ Proceso frida-inject detenido.")
    }

    // ── Núcleo ───────────────────────────────────────────────────────────────

    private suspend fun injectInternal(target: String, scriptPath: String): Boolean = withContext(Dispatchers.IO) {
        // Destruir cualquier inyección previa
        activeProcess?.destroy()
        activeProcess = null

        logCallback("📝 Iniciando frida-inject ($target)...")

        val process = try {
            // stderr redirigido a stdout para capturar todo en un solo stream
            val cmd = "su -c \"$FRIDA_INJECT_PATH $target $scriptPath 2>&1\""
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        } catch (e: Exception) {
            logCallback("❌ No se pudo lanzar frida-inject: ${e.message}")
            return@withContext false
        }

        activeProcess = process

        // ── Leer stdout en tiempo real ───────────────────────────────────────
        val reader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        var confirmed = false

        while (System.currentTimeMillis() < deadline) {
            // Si el proceso ya murió, salir del loop
            try {
                process.exitValue()
                break
            } catch (_: IllegalThreadStateException) { /* vivo */ }

            if (reader.ready()) {
                val line = reader.readLine() ?: break
                logCallback(line)

                when {
                    // Marcador principal: confirmación explícita
                    line.contains(CONFIRM_MARKER) -> {
                        confirmed = true
                        break
                    }

                    // Fallback: si el diagnóstico terminó sin error, también es OK
                    line.contains("RUNTIME DIAGNOSTICS FINISHED") ||
                        line.contains("DIAGNÓSTICO FINALIZADO") -> {
                        confirmed = true
                        // No cortamos — seguimos leyendo para capturar el marcador
                    }

                    // Errores conocidos de frida-inject → salir rápido
                    line.contains("Failed to attach") ||
                        line.contains("Unable to find process") -> {
                        logCallback("❌ frida-inject reportó un error fatal.")
                        process.destroy()
                        activeProcess = null
                        return@withContext false
                    }
                }
            } else {
                delay(READ_POLL_MS)
            }
        }

        return@withContext if (confirmed) {
            logCallback("✅ Inyección confirmada.")
            // El proceso sigue vivo — frida-inject debe permanecer adjunto
            true
        } else {
            logCallback("⚠️ Tiempo de espera agotado sin confirmar inyección.")
            // Mantenemos el proceso: puede estar corriendo aunque no vimos el marcador
            false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilidades de shell reutilizables
// ─────────────────────────────────────────────────────────────────────────────

object ShellUtils {

    /**
     * Ejecuta un comando como root y devuelve (exitCode, stdout+stderr combinados).
     * Uso: val (code, out) = ShellUtils.runAsRoot("pm list packages")
     */
    suspend fun runAsRoot(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime()
            .exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText() +
            process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        Pair(code, output.trim())
    }

    /**
     * Verifica si frida-inject está instalado en el dispositivo.
     */
    suspend fun isFridaInjectInstalled(): Boolean = withContext(Dispatchers.IO) {
        val (code, _) = runAsRoot("test -f $FRIDA_INJECT_PATH && echo ok")
        code == 0
    }

    /**
     * Devuelve el PID de un proceso por nombre de paquete, o null si no corre.
     */
    suspend fun getPidByPackage(packageName: String): Int? = withContext(Dispatchers.IO) {
        val (_, out) = runAsRoot("pidof $packageName")
        out.trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull()
    }
}
