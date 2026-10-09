@file:OptIn(ExperimentalMaterial3Api::class)

package com.hackpuntes.fridagate.ui.screens.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.ApplicationInfo
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

    val installedApps = remember(context) {
        context.packageManager.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
            .map { info ->
                context.packageManager.getApplicationLabel(info).toString() to info.packageName
            }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
    }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    val importJsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val code = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported.js"
                viewModel.importScript(name, code)
            }.onFailure {
                viewModel.addLog("❌ No se pudo importar el archivo .js: ${it.message}")
            }
        }
    }
    
    var selectedTab by remember { mutableStateOf(0) }
    var showConfirmDelete by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<FridaScript?>(null) }
    var selectedTargetApp by remember { mutableStateOf<String?>(null) }
    var rootBypassEnabled by remember { mutableStateOf(false) }
    var sslBypassEnabled by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        TopAppBar(
            title = { Text("📝 Script Manager") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            },
            actions = {
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            }
        )
        
        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                text = { Text("Scripts", fontSize = 12.sp) },
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.List, null) }
            )
            Tab(
                text = { Text("Editor", fontSize = 12.sp) },
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.Edit, null) }
            )
            Tab(
                text = { Text("Logs", fontSize = 12.sp) },
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                icon = { Icon(Icons.Default.Info, null) }
            )
        }
        
        // Contenido
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when (selectedTab) {
                0 -> ScriptListTab(
                    scripts = scripts,
                    selectedScript = selectedScript,
                    selectedTargetApp = selectedTargetApp,
                    onSelectTargetApp = { selectedTargetApp = it },
                    rootBypassEnabled = rootBypassEnabled,
                    onRootBypassToggle = { rootBypassEnabled = it },
                    sslBypassEnabled = sslBypassEnabled,
                    onSslBypassToggle = { sslBypassEnabled = it },
                    onSelectScript = { script ->
                        viewModel.selectScript(script)
                        selectedTab = 1
                    },
                    onNewScript = {
                        viewModel.createNewScript()
                        selectedTab = 1
                    },
                    onDeleteScript = { script ->
                        scriptToDelete = script
                        showConfirmDelete = true
                    }
                )
                1 -> EditorTab(
                    viewModel = viewModel,
                    selectedScript = selectedScript,
                    editorCode = editorCode,
                    onCodeChange = { viewModel.updateEditorCode(it) },
                    onSave = { name ->
                        viewModel.saveCurrentScript(name)
                        viewModel.clearMessage()
                    }
                )
                2 -> LogsTab(
                    logs = logs,
                    isExecuting = isExecuting,
                    onClearLogs = { viewModel.clearLogs() },
                    onExecute = { viewModel.executeScript() },
                    onStop = { viewModel.stopScript() },
                    onExportLogs = {
                        viewModel.exportLogs(context)
                    }
                )
            }
        }
    }
    
    // Dialog de confirmación de eliminación
    if (showConfirmDelete && scriptToDelete != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Eliminar Script") },
            text = { Text("¿Eliminar '${scriptToDelete!!.name}'?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteScript(scriptToDelete!!.id)
                    showConfirmDelete = false
                }) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                Button(onClick = { showConfirmDelete = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// ==================== SCRIPT LIST TAB ====================

@Composable
fun ScriptListTab(
    scripts: List<FridaScript>,
    selectedScript: FridaScript?,
    selectedTargetApp: String?,
    onSelectTargetApp: (String) -> Unit,
    rootBypassEnabled: Boolean,
    onRootBypassToggle: (Boolean) -> Unit,
    sslBypassEnabled: Boolean,
    onSslBypassToggle: (Boolean) -> Unit,
    onSelectScript: (FridaScript) -> Unit,
    onNewScript: () -> Unit,
    onDeleteScript: (FridaScript) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        // TARGET APP SECTION
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📱 Target App", style = MaterialTheme.typography.titleSmall)
                    ExposedDropdownMenuBox(
                        expanded = targetMenuExpanded,
                        onExpandedChange = { targetMenuExpanded = !targetMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = installedApps.firstOrNull { it.second == selectedTargetApp }?.let { "${it.first} (${it.second})" }
                                ?: selectedTargetApp.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Seleccionar aplicación instalada") },
                            placeholder = { Text("Elige una app") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetMenuExpanded) },
                            modifier = Modifier.fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = targetMenuExpanded,
                            onDismissRequest = { targetMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            installedApps.forEach { (label, packageName) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(label)
                                            Text(packageName, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline)
                                        }
                                    },
                                    onClick = {
                                        onSelectTargetApp(packageName)
                                        targetMenuExpanded = false
                                    }
                                )
                            }
                            if (installedApps.isEmpty()) {
                                DropdownMenuItem(text = { Text("No se encontraron aplicaciones") }, onClick = {})
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
                Text("Nuevo Script")
            }
        }
        
        // IMPORT BUTTON
        item {
            Button(
                onClick = { importJsLauncher.launch(arrayOf("application/javascript", "text/javascript", "application/x-javascript", "text/plain")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.padding(end = 8.dp))
                Text("📂 Importar .js")
            }
        }
        
        // DIVIDER
        item {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        
        // PREDEFINED SCRIPTS SECTION
        item {
            Text(
                "🛡️ Scripts Predefinidos",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp)
            )
        }
        
        // Root Detection Bypass Toggle
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
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
                            "Root Detection Bypass",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Script de Frida",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = rootBypassEnabled,
                        onCheckedChange = onRootBypassToggle
                    )
                }
            }
        }
        
        // SSL Pinning Bypass Toggle
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
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
                            "SSL Pinning Bypass",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Script de Frida",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = sslBypassEnabled,
                        onCheckedChange = onSslBypassToggle
                    )
                }
            }
        }
        
        // DIVIDER
        item {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }
        
        // USER SCRIPTS SECTION
        item {
            Text(
                "📝 Mis Scripts",
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
                    onDelete = { onDeleteScript(script) }
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
    onDelete: () -> Unit
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
                    Text("Sin logs", color = MaterialTheme.colorScheme.outline)
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
