@file:OptIn(ExperimentalMaterial3Api::class)

package com.hackpuntes.fridagate.ui.screens.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackpuntes.fridagate.data.models.FridaScript
import com.hackpuntes.fridagate.ui.viewmodels.ScriptsViewModel
import com.hackpuntes.fridagate.ui.extras.ExtrasViewModel
import com.hackpuntes.fridagate.utils.ScriptUtils
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScriptsScreen(
    viewModel: ScriptsViewModel,
    onBack: () -> Unit
) {
    val scripts by viewModel.scripts.collectAsState()
    val selectedScript by viewModel.selectedScript.collectAsState()
    val editorCode by viewModel.editorCode.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasStorageAccess by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        // Update the status as soon as Android returns from the permission settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Permiso para guardar scripts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hasStorageAccess)
                                "Permiso de almacenamiento concedido. Ya puedes guardar y exportar archivos."
                            else
                                "Para guardar tus archivos en Documentos/Fridagate2.0/Scripts, concede acceso a archivos desde los ajustes de Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasStorageAccess) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasStorageAccess) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Permiso concedido", color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    runCatching { context.startActivity(intent) }
                                        .onFailure {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Conceder permiso de almacenamiento")
                            }
                        }
                    }
                }
            }
        }

        // NEW SCRIPT BUTTON
        item {
            Button(
                onClick = onNewScript,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = 8.dp))
                Text("Nuevo script")
            }
        }
        
        // IMPORT BUTTON
        item {
            Button(
                onClick = onImportScript,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.padding(end = 8.dp))
                Text("📂 Importar archivo .js")
            }
        }
        
        // DIVIDER
        item {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        
        // PREDEFINED SCRIPTS SECTION
        item {
            Text(
                "🛡️ Scripts predefinidos",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp)
            )
        }
        items(bypassScripts) { script ->
            var showSource by remember(script.id) { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                script.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                script.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(
                            checked = enabledBypassScripts.contains(script.id),
                            onCheckedChange = { onToggleBypassScript(script.id) },
                            enabled = !bypassLoading
                        )
                    }
                    TextButton(onClick = { showSource = !showSource }) {
                        Text(if (showSource) "Ocultar código" else "Ver código (solo lectura)")
                    }
                    if (showSource) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            val source = remember(script.id) {
                                runCatching {
                                    context.assets.open(script.assetPath).bufferedReader().use { it.readText() }
                                }.getOrDefault("No se pudo leer el script.")
                            }
                            LazyColumn(modifier = Modifier.padding(8.dp)) {
                                items(source.lines()) { line ->
                                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onLaunchWithBypass,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                enabled = !bypassLoading && fridaInjectReady && selectedTargetApp.isNotBlank() &&
                (enabledBypassScripts.isNotEmpty() || scripts.any { it.enabledForLaunch })
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Lanzar aplicación")
            }
            if (!fridaInjectReady) {
                Text(
                    "Instala frida-inject desde la pestaña Frida para habilitar la inyección.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }

        // DIVIDER
        item {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        
        // USER SCRIPTS SECTION
        item {
            Text(
                "📝 Mis scripts",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp)
            )
        }
        
        // Scripts List
        if (scripts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .wrapContentSize(Alignment.Center)
                ) {
                    Text(
                        "No hay scripts. Crea uno nuevo.",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            items(scripts) { script ->
                ScriptListItem(
                    script = script,
                    isSelected = selectedScript?.id == script.id,
                    onSelect = { onSelectScript(script) },
                    onExport = { onExportScript(script) },
                    onDelete = { onDeleteScript(script) },
                    isEnabledForLaunch = script.enabledForLaunch,
                    onToggleEnabled = { enabled -> onToggleUserScript(script, enabled) }
                )
            }
        }
    }
}

@Composable
fun ScriptListItem(
    script: FridaScript,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    isEnabledForLaunch: Boolean,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onSelect)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                
                Text(
                    text = "Líneas: ${script.code.lines().size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                
                if (script.supportsIL2CPP) {
                    Text(
                        text = "✓ Soporta IL2CPP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                Text(
                    text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                        Date(script.updatedAt)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            Switch(
                checked = isEnabledForLaunch,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color(0xFF00C853),
                    checkedTrackColor = androidx.compose.ui.graphics.Color(0xFF69F0AE),
                    uncheckedThumbColor = MaterialTheme.colorScheme.error,
                    uncheckedTrackColor = MaterialTheme.colorScheme.errorContainer
                )
            )
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Exportar script a Descargas",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Eliminar",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ==================== EDITOR TAB ====================

@Composable
fun EditorTab(
    viewModel: ScriptsViewModel,
    selectedScript: FridaScript?,
    editorCode: String,
    onCodeChange: (String) -> Unit,
    onSave: (String) -> Unit
) {
    var scriptName by remember { mutableStateOf(selectedScript?.name ?: "") }
    
    LaunchedEffect(selectedScript) {
        scriptName = selectedScript?.name ?: ""
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Script name input
        TextField(
            value = scriptName,
            onValueChange = { scriptName = it },
            label = { Text("Nombre del script") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            singleLine = true
        )
        
        // Templates buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { viewModel.insertBasicTemplate() },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("📄 Básico", fontSize = 10.sp)
            }
            Button(
                onClick = { viewModel.insertIL2CPPTemplate() },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("🔗 IL2CPP", fontSize = 10.sp)
            }
        }
        
        // Code editor
        TextField(
            value = editorCode,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            placeholder = { Text("Escribe tu código Frida aquí...") },
            singleLine = false
        )
        
        // Save button
        Button(
            onClick = { onSave(scriptName) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.padding(end = 8.dp))
            Text("💾 Guardar")
        }
    }
}

// ==================== LOGS TAB ====================

@Composable
fun LogsTab(
    logs: List<String>,
    isExecuting: Boolean,
    onClearLogs: () -> Unit,
    onExecute: () -> Unit,
    onStop: () -> Unit,
    onExportLogs: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Logs display
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center)
                ) {
                    Text("Sin registros", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { log ->
                        Text(
                            text = log,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            color = when {
                                log.contains("✅") || log.contains("▶️") -> MaterialTheme.colorScheme.primary
                                log.contains("❌") -> MaterialTheme.colorScheme.error
                                log.contains("⚠️") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
        
        // Action buttons
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onExecute,
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("▶️ Ejecutar", fontSize = 11.sp)
                }
                Button(
                    onClick = onStop,
                    enabled = isExecuting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("⏹️ Detener", fontSize = 11.sp)
                }
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onClearLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🗑️ Limpiar", fontSize = 11.sp)
                }
                Button(
                    onClick = onExportLogs,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("💾 Exportar", fontSize = 11.sp)
                }
            }
        }
    }
}
