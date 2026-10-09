package com.hackpuntes.fridagate.ui.viewmodels

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.data.models.FridaScript
import com.hackpuntes.fridagate.data.repository.ScriptRepository
import com.hackpuntes.fridagate.utils.FridaInjectUtils
import com.hackpuntes.fridagate.utils.ScriptUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScriptsViewModel(private val repository: ScriptRepository) : ViewModel() {
    
    // Estado de scripts
    private val _scripts = MutableStateFlow<List<FridaScript>>(emptyList())
    val scripts = _scripts.asStateFlow()
    
    // Script seleccionado actualmente
    private val _selectedScript = MutableStateFlow<FridaScript?>(null)
    val selectedScript = _selectedScript.asStateFlow()
    
    // Código en edición
    private val _editorCode = MutableStateFlow("")
    val editorCode = _editorCode.asStateFlow()
    
    // Estado de ejecución
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting = _isExecuting.asStateFlow()
    
    // Logs en tiempo real
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()
    
    // Target app seleccionada (PID)
    private val _targetAppPid = MutableStateFlow<Int?>(null)
    val targetAppPid = _targetAppPid.asStateFlow()
    
    // Mensajes de error/éxito
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    
    init {
        loadScripts()
    }
    
    // ==================== CRUD OPERATIONS ====================
    
    fun loadScripts() = viewModelScope.launch {
        val allScripts = repository.getAllScripts()
        _scripts.value = allScripts
    }
    
    fun createScript(name: String, code: String) = viewModelScope.launch {
        val script = FridaScript(
            name = name,
            code = code,
            supportsIL2CPP = code.contains("frida-il2cpp-bridge") || code.contains("import \"frida-il2cpp-bridge\"")
        )
        
        val success = repository.createScript(script)
        if (success) {
            _message.value = "✅ Script creado: $name"
            loadScripts()
        } else {
            _message.value = "❌ Error al crear script"
        }
    }
    
    fun updateScript(script: FridaScript) = viewModelScope.launch {
        val success = repository.updateScript(script)
        if (success) {
            _message.value = "✅ Script actualizado"
            loadScripts()
        } else {
            _message.value = "❌ Error al actualizar"
        }
    }
    
    fun deleteScript(scriptId: String) = viewModelScope.launch {
        val success = repository.deleteScript(scriptId)
        if (success) {
            _message.value = "✅ Script eliminado"
            if (_selectedScript.value?.id == scriptId) {
                _selectedScript.value = null
                _editorCode.value = ""
            }
            loadScripts()
        } else {
            _message.value = "❌ Error al eliminar"
        }
    }
    
    // ==================== EDITOR OPERATIONS ====================
    
    fun selectScript(script: FridaScript) {
        _selectedScript.value = script
        _editorCode.value = script.code
    }
    
    fun createNewScript() {
        _selectedScript.value = null
        _editorCode.value = ""
    }
    
    fun updateEditorCode(code: String) {
        _editorCode.value = code
    }
    
    fun saveCurrentScript(name: String) = viewModelScope.launch {
        val code = _editorCode.value
        
        if (code.isEmpty()) {
            _message.value = "⚠️ El script está vacío"
            return@launch
        }
        
        val selected = _selectedScript.value
        
        if (selected != null) {
            val updated = selected.copy(
                code = code,
                supportsIL2CPP = code.contains("frida-il2cpp-bridge")
            )
            updateScript(updated)
        } else {
            createScript(name.ifEmpty { "Script ${System.currentTimeMillis()}" }, code)
        }
    }
    
    fun importScript(name: String, code: String) = viewModelScope.launch {
        val cleanName = name.substringBeforeLast('.', name).ifBlank { "Script importado" }
        if (code.isBlank()) {
            _message.value = "⚠️ El archivo está vacío"
            return@launch
        }
        val script = FridaScript(
            name = cleanName,
            code = code,
            supportsIL2CPP = code.contains("frida-il2cpp-bridge", ignoreCase = true)
        )
        if (repository.createScript(script)) {
            _scripts.value = repository.getAllScripts()
            _message.value = "✅ Script importado: $cleanName"
        } else {
            _message.value = "❌ No se pudo guardar el script importado. Comprueba el almacenamiento."
        }
    }

    // ==================== EXECUTION ====================
    
    fun setTargetApp(pid: Int?) {
        _targetAppPid.value = pid
    }
    
    fun executeScript(context: Context, packageName: String) = viewModelScope.launch {
        val script = _selectedScript.value
        val code = _editorCode.value

        if (code.isBlank()) {
            addLog("❌ Error: No hay código para ejecutar")
            return@launch
        }
        if (packageName.isBlank()) {
            addLog("❌ Error: Selecciona una app de destino primero")
            return@launch
        }

        _isExecuting.value = true
        addLog("▶️ Ejecutando: ${script?.name ?: "Script del editor"} en $packageName")
        try {
            val result = FridaInjectUtils.launchWithCustomScript(
                context = context,
                scriptName = script?.name ?: "Script del editor",
                scriptCode = code,
                packageName = packageName
            )
            result.forEach { addLog(it) }
        } catch (e: Exception) {
            addLog("❌ Error de inyección: ${e.message}")
        } finally {
            _isExecuting.value = false
        }
    }

    fun setUserScriptEnabled(scriptId: String, enabled: Boolean) = viewModelScope.launch {
        val script = _scripts.value.firstOrNull { it.id == scriptId } ?: return@launch
        val updated = script.copy(enabledForLaunch = enabled)
        _scripts.update { current -> current.map { if (it.id == scriptId) updated else it } }
        if (!repository.updateScript(updated)) {
            addLog("❌ No se pudo guardar el estado del script ${script.name}")
            loadScripts()
        }
    }

    fun launchEnabledScripts(
        context: Context,
        packageName: String,
        enabledBuiltInScripts: List<ScriptUtils.BypassScript>
    ) = viewModelScope.launch {
        if (packageName.isBlank()) {
            addLog("❌ Error: Selecciona una aplicación de destino primero")
            return@launch
        }
        val enabledUserScripts = _scripts.value.filter { it.enabledForLaunch }
        if (enabledBuiltInScripts.isEmpty() && enabledUserScripts.isEmpty()) {
            addLog("⚠️ Activa al menos un script antes de lanzar la aplicación")
            return@launch
        }

        _isExecuting.value = true
        addLog("▶️ Lanzando $packageName con ${enabledBuiltInScripts.size + enabledUserScripts.size} script(s) activado(s)")
        try {
            val result = FridaInjectUtils.launchWithCombinedScripts(
                context = context,
                bypassScripts = enabledBuiltInScripts,
                customScripts = enabledUserScripts,
                packageName = packageName
            )
            result.forEach { addLog(it) }
        } catch (e: Exception) {
            addLog("❌ Error de inyección: ${e.message}")
        } finally {
            _isExecuting.value = false
            loadScripts()
        }
    }

    fun stopScript() = viewModelScope.launch {
        val result = FridaInjectUtils.stopCustomScript()
        result.forEach { addLog(it) }
        _isExecuting.value = false
    }

    // ==================== LOGS ====================
    
    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        
        _logs.update { current ->
            (current + logLine).takeLast(500)
        }
    }
    
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("📋 Logs limpiados")
    }
    
    fun exportLogs(context: Context): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val fileName = "fridagate_logs_$timestamp.txt"
            val file = File(context.externalCacheDir, fileName)
            
            val content = _logs.value.joinToString("\n")
            file.writeText(content)
            
            _message.value = "✅ Logs exportados a: ${file.absolutePath}"
            file.absolutePath
        } catch (e: Exception) {
            _message.value = "❌ Error al exportar: ${e.message}"
            null
        }
    }
    
    fun exportScript(context: Context, scriptId: String): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val fileName = "frida_script_$timestamp.js"
            val file = File(context.externalCacheDir, fileName)
            
            val script = _selectedScript.value
            if (script != null) {
                file.writeText(script.code)
                _message.value = "✅ Script exportado"
                file.absolutePath
            } else {
                _message.value = "❌ Error: Script no encontrado"
                null
            }
        } catch (e: Exception) {
            _message.value = "❌ Error: ${e.message}"
            null
        }
    }
    
    fun exportScriptToDownloads(context: Context, script: FridaScript) = viewModelScope.launch(Dispatchers.IO) {
        val safeName = script.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "script" }
        val fileName = if (safeName.endsWith(".js", ignoreCase = true)) safeName else "$safeName.js"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/javascript")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")
                try {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(script.code) }
                        ?: throw IllegalStateException("No se pudo escribir el archivo")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    _message.value = "✅ Exportado a Descargas/$fileName"
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("No se pudo acceder a Descargas")
                File(dir, fileName).writeText(script.code)
                _message.value = "✅ Exportado a Descargas/$fileName"
            }
        } catch (e: Exception) {
            _message.value = "❌ Error al exportar: ${e.message}"
        }
    }

    fun clearMessage() {
        _message.value = ""
    }
    
    // ==================== TEMPLATES ====================
    
    fun insertIL2CPPTemplate() {
        val template = """
            import "frida-il2cpp-bridge";
            
            function main() {
                console.log("IL2CPP Bridge loaded successfully!");
                
                // Encuentra una clase
                // const MyClass = IL2CPP.classes["Assembly-CSharp.MyNamespace.MyClass"];
                
                // Instancia un objeto
                // const instance = MyClass.${'$'}new();
                
                // Llama un método
                // instance.MyMethod(param1, param2);
                
                console.log("Script finished");
            }
            
            if (Java.available) {
                main();
            }
        """.trimIndent()
        
        _editorCode.value = template
    }
    
    fun insertBasicTemplate() {
        val template = """
            // Script básico de Frida
            console.log("Script started!");
            
            // Hook a un método Java
            var MainActivity = Java.use("com.example.MainActivity");
            
            MainActivity.onCreate.implementation = function(savedInstanceState) {
                console.log("onCreate called!");
                return this.onCreate(savedInstanceState);
            };
            
            console.log("Hooks installed!");
        """.trimIndent()
        
        _editorCode.value = template
    }
}

// Factory para crear el ViewModel
class ScriptsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScriptsViewModel(ScriptRepository(context)) as T
    }
}
