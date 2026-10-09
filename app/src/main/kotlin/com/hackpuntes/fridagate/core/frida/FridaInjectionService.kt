package com.hackpuntes.fridagate.core.frida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Servicio para inyectar scripts Frida en procesos objetivo
 * Requiere: frida-server corriendo en el dispositivo
 * Default: localhost:27042
 */
class FridaInjectionService(
    private val context: Context,
    private val fridaHost: String = "localhost",
    private val fridaPort: Int = 27042,
    private val logCallback: (String) -> Unit = {}
) {
    
    private var socket: Socket? = null
    private var isConnected = false
    
    // ==================== CONNECTION ====================
    
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            socket = Socket(fridaHost, fridaPort)
            isConnected = true
            logCallback("✅ Conectado a Frida en $fridaHost:$fridaPort")
            true
        } catch (e: Exception) {
            isConnected = false
            logCallback("❌ Error de conexión: ${e.message}")
            false
        }
    }
    
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            socket?.close()
            isConnected = false
            logCallback("🔌 Desconectado de Frida")
        } catch (e: Exception) {
            logCallback("⚠️ Error al desconectar: ${e.message}")
        }
    }
    
    // ==================== INJECTION ====================
    
    suspend fun injectScript(
        targetPid: Int,
        scriptCode: String,
        supportIL2CPP: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected && !connect()) {
            return@withContext false
        }
        
        return@withContext try {
            logCallback("📤 Inyectando en PID: $targetPid")
            
            // Preparar código
            val preparedCode = prepareScript(scriptCode, supportIL2CPP)
            
            // Enviar petición a Frida
            val request = FridaRequest.inject(targetPid, preparedCode)
            val response = sendFridaRequest(request)
            
            if (response != null && response.isSuccess) {
                logCallback("✅ Script inyectado correctamente")
                true
            } else {
                logCallback("❌ Frida rechazó la inyección: ${response?.error}")
                false
            }
        } catch (e: Exception) {
            logCallback("❌ Error en inyección: ${e.message}")
            false
        }
    }
    
    // ==================== HELPERS ====================
    
    private fun prepareScript(code: String, supportIL2CPP: Boolean): String {
        val prepared = if (supportIL2CPP) {
            // Asegurar importación correcta de il2cpp-bridge
            if (!code.contains("frida-il2cpp-bridge")) {
                "import \"frida-il2cpp-bridge\";\n$code"
            } else {
                code
            }
        } else {
            code
        }
        
        // Envolver en try-catch
        return """
            try {
                $prepared
            } catch (error) {
                console.log("Script error: " + error.message);
                console.log(error.stack);
            }
        """.trimIndent()
    }
    
    private suspend fun sendFridaRequest(request: String): FridaResponse? = withContext(Dispatchers.IO) {
        return@withContext try {
            if (socket == null || !isConnected) return@withContext null
            
            val output = socket!!.getOutputStream()
            output.write((request + "\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
            
            // Leer respuesta
            val input = socket!!.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val response = reader.readLine()
            
            if (response != null) {
                FridaResponse.parse(response)
            } else {
                null
            }
        } catch (e: Exception) {
            logCallback("❌ Error de comunicación: ${e.message}")
            null
        }
    }
}

// ==================== FRIDA PROTOCOL ====================

object FridaRequest {
    fun inject(pid: Int, code: String): String {
        return """
            {
                "type": "attach",
                "pid": $pid,
                "script": "${escapeJson(code)}"
            }
        """.trimIndent()
    }
    
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

data class FridaResponse(
    val isSuccess: Boolean,
    val error: String? = null,
    val data: String? = null
) {
    companion object {
        fun parse(json: String): FridaResponse {
            return try {
                when {
                    json.contains("\"ok\"") || json.contains("success") -> {
                        FridaResponse(isSuccess = true)
                    }
                    json.contains("error") -> {
                        val errorMsg = json.substringAfter("\"error\":\"").substringBefore("\"")
                        FridaResponse(isSuccess = false, error = errorMsg)
                    }
                    else -> FridaResponse(isSuccess = true, data = json)
                }
            } catch (e: Exception) {
                FridaResponse(isSuccess = true, data = json)
            }
        }
    }
}

// ==================== LEGACY: Shell-based Injection ====================

/**
 * Alternativa para dispositivos sin Frida socket directo
 * Usa comandos shell (requiere root)
 */
class FridaInjectionServiceShell(
    private val context: Context,
    private val logCallback: (String) -> Unit = {}
) {
    
    suspend fun injectViaShell(
        targetPid: Int,
        scriptPath: String
    ): Boolean = withContext(Dispatchers.Default) {
        return@withContext try {
            logCallback("📝 Inyectando vía shell...")
            
            // Este es un placeholder - la inyección real requiere frida-inject
            // Comando típico: /data/local/tmp/frida-inject PID /data/local/tmp/script.js
            
            val command = "frida-inject $targetPid $scriptPath"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logCallback("✅ Inyección exitosa")
                true
            } else {
                val errorStream = process.errorStream.bufferedReader().readText()
                logCallback("❌ Error: $errorStream")
                false
            }
        } catch (e: Exception) {
            logCallback("❌ Error: ${e.message}")
            false
        }
    }
}
