package com.hackpuntes.fridagate.data.repository

import android.content.Context
import com.hackpuntes.fridagate.data.models.FridaScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScriptRepository(private val context: Context) {
    
    private val scriptsDir: File
        get() {
            val dir = File(context.filesDir, "frida_scripts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    
    // CREATE - Guardar nuevo script
    suspend fun createScript(script: FridaScript): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(scriptsDir, "${script.id}.js")
            val metadata = File(scriptsDir, "${script.id}.meta")
            
            // Guardar código
            file.writeText(script.code)
            
            // Guardar metadatos (nombre, descripción, tags, etc)
            val metaData = """
                name=${script.name}
                description=${script.description}
                createdAt=${script.createdAt}
                updatedAt=${script.updatedAt}
                supportsIL2CPP=${script.supportsIL2CPP}
                tags=${script.tags.joinToString(",")}
            """.trimIndent()
            metadata.writeText(metaData)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // READ - Obtener un script por ID
    suspend fun getScriptById(scriptId: String): FridaScript? = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(scriptsDir, "$scriptId.js")
            if (!file.exists()) return@withContext null
            
            val code = file.readText()
            val metadata = parseMetadata(scriptId)
            
            FridaScript(
                id = scriptId,
                name = metadata["name"] ?: scriptId,
                code = code,
                description = metadata["description"] ?: "",
                createdAt = metadata["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = metadata["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                supportsIL2CPP = metadata["supportsIL2CPP"]?.toBoolean() ?: false,
                tags = metadata["tags"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // UPDATE - Actualizar script existente
    suspend fun updateScript(script: FridaScript): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val updatedScript = script.copy(updatedAt = System.currentTimeMillis())
            val file = File(scriptsDir, "${script.id}.js")
            val metadata = File(scriptsDir, "${script.id}.meta")
            
            file.writeText(updatedScript.code)
            
            val metaData = """
                name=${updatedScript.name}
                description=${updatedScript.description}
                createdAt=${updatedScript.createdAt}
                updatedAt=${updatedScript.updatedAt}
                supportsIL2CPP=${updatedScript.supportsIL2CPP}
                tags=${updatedScript.tags.joinToString(",")}
            """.trimIndent()
            metadata.writeText(metaData)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // DELETE - Borrar script
    suspend fun deleteScript(scriptId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            File(scriptsDir, "$scriptId.js").delete() &&
            File(scriptsDir, "$scriptId.meta").delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // READ ALL - Obtener todos los scripts
    suspend fun getAllScripts(): List<FridaScript> = withContext(Dispatchers.IO) {
        return@withContext try {
            scriptsDir.listFiles { file ->
                file.extension == "js"
            }?.mapNotNull { file ->
                val scriptId = file.nameWithoutExtension
                getScriptById(scriptId)
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    // Exportar script a TXT
    suspend fun exportScript(scriptId: String, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val script = getScriptById(scriptId) ?: return@withContext false
            val exportFile = File(targetPath)
            
            val content = """
                ===== FRIDAGATE SCRIPT EXPORT =====
                Nombre: ${script.name}
                Descripción: ${script.description}
                Soporta IL2CPP: ${script.supportsIL2CPP}
                Creado: ${script.createdAt}
                Actualizado: ${script.updatedAt}
                Tags: ${script.tags.joinToString(", ")}
                
                ===== CÓDIGO =====
                ${script.code}
            """.trimIndent()
            
            exportFile.writeText(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Importar script desde archivo
    suspend fun importScript(filePath: String): FridaScript? = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val code = file.readText()
            val scriptName = file.nameWithoutExtension
            
            val script = FridaScript(
                name = scriptName,
                code = code,
                supportsIL2CPP = code.contains("frida-il2cpp-bridge")
            )
            
            createScript(script)
            script
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // Helper - Parsear metadatos
    private suspend fun parseMetadata(scriptId: String): Map<String, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val metaFile = File(scriptsDir, "$scriptId.meta")
            if (!metaFile.exists()) return@withContext emptyMap()
            
            metaFile.readLines()
                .associate { line ->
                    val (key, value) = line.split("=", limit = 2).let { parts ->
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                    key to value
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }
}
