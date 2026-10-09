@file:OptIn(ExperimentalMaterial3Api::class)

package com.hackpuntes.fridagate.ui.screens.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackpuntes.fridagate.data.models.FridaScript
import com.hackpuntes.fridagate.ui.viewmodels.ScriptsViewModel
import com.hackpuntes.fridagate.ui.extras.ExtrasViewModel
import com.hackpuntes.fridagate.utils.ScriptUtils
import com.hackpuntes.fridagate.utils.RootUtils
import com.hackpuntes.fridagate.utils.FrameworkDetector
import com.hackpuntes.fridagate.utils.FrameworkTestScript
import com.hackpuntes.fridagate.utils.FrameworkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModelProvider
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
    var isRootAvailable by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        isRootAvailable = withContext(Dispatchers.IO) { RootUtils.isRootAvailable() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    isRootAvailable = RootUtils.isRootAvailable()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val extrasViewModel: ExtrasViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ExtrasViewModel(context) as T
        }
    )
    val targetPackage by extrasViewModel.targetPackage.collectAsState()
    val enabledBypassScripts by extrasViewModel.enabledScripts.collectAsState()
    val bypassLoading by extrasViewModel.isLoading.collectAsState()
    val fridaInjectReady by extrasViewModel.isFridaInjectInstalled.collectAsState()
    val bypassLogs by extrasViewModel.logs.collectAsState()

    val allInstalledApps = remember(context) {
        context.packageManager.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
            .map { info ->
                val flags = info.flags
                val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                Triple(
                    context.packageManager.getApplicationLabel(info).toString(),
                    info.packageName,
                    isSystem
                )
            }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
    }
    var appFilter by remember { mutableStateOf("Usuario") }
    val installedApps = remember(allInstalledApps, appFilter) {
        when (appFilter) {
            "Sistema" -> allInstalledApps.filter { it.third }
            "Usuario" -> allInstalledApps.filter { !it.third }
            else -> allInstalledApps
        }
    }
    var appFilterMenuExpanded by remember { mutableStateOf(false) }
    var targetMenuExpanded by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }
    var detectedFrameworks by remember { mutableStateOf<Map<String, FrameworkInfo>>(emptyMap()) }
    var frameworkTestEnabled by remember(targetPackage) { mutableStateOf(false) }

    // Analiza únicamente la aplicación seleccionada. Cambiar o quitar la selección
    // cancela esta corrutina y evita recorrer todas las aplicaciones del dispositivo.
    LaunchedEffect(targetPackage) {
        val packageName = targetPackage
        if (packageName.isBlank() || detectedFrameworks.containsKey(packageName)) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) {
            FrameworkDetector.detect(context, packageName)
        }
        detectedFrameworks = detectedFrameworks + (packageName to info)
    }

    LaunchedEffect(selectedTab, targetPackage) {
        if (selectedTab == 2) {
            repeat(Int.MAX_VALUE) {
                viewModel.refreshInjectionLogs()
                delay(1200)
            }
        }
    }

    val importJsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Script.js"
                if (!name.endsWith(".js", ignoreCase = true)) {
                    viewModel.addLog("⚠️ Selecciona un archivo con extensión .js")
                } else {
                    val code = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    viewModel.importScript(name, code)
                }
            }.onFailure {
                viewModel.addLog("❌ No se pudo importar el archivo .js: ${it.message}")
            }
        }
    }
    
    var showConfirmDelete by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<FridaScript?>(null) }
    var showConfirmExport by remember { mutableStateOf(false) }
    var scriptToExport by remember { mutableStateOf<FridaScript?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Target app selector is intentionally above the Script Manager header.
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("📱 Aplicación de destino", style = MaterialTheme.typography.titleSmall)
                    Box {
                        TextButton(onClick = { if (isRootAvailable) appFilterMenuExpanded = true }, enabled = isRootAvailable) {
                            Text(appFilter)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Filtrar aplicaciones")
                        }
                        DropdownMenu(
                            expanded = appFilterMenuExpanded,
                            onDismissRequest = { appFilterMenuExpanded = false }
                        ) {
                            listOf("Usuario", "Sistema", "Usuario + Sistema").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    leadingIcon = {
                                        if (appFilter == option) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        appFilter = option
                                        viewModel.addLog("Filtro de aplicaciones seleccionado: $option")
                                        appFilterMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                val selectedApp = allInstalledApps.firstOrNull { it.second == targetPackage }
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (targetPackage.isBlank()) {
                        OutlinedButton(
                            onClick = { if (isRootAvailable) targetMenuExpanded = true },
                            enabled = isRootAvailable,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text("Seleccionar una app", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Abrir selector de aplicaciones")
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                selectedApp?.first ?: targetPackage,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    targetPackage,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2
                                )
                                AppPackageIcon(targetPackage)
                                IconButton(
                                    onClick = {
                                        val removedPackage = targetPackage
                                        extrasViewModel.setTargetPackage("")
                                        viewModel.addLog("Aplicación de destino retirada: $removedPackage. Análisis detenido.")
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Quitar aplicación seleccionada y detener análisis",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(
                                    onClick = { if (isRootAvailable) targetMenuExpanded = true },
                                    enabled = isRootAvailable,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Cambiar aplicación de destino"
                                    )
                                }
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = targetMenuExpanded,
                        onDismissRequest = { targetMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        installedApps.forEach { (label, packageName, _) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(
                                            packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                },
                                leadingIcon = { AppPackageIcon(packageName) },
                                onClick = {
                                    extrasViewModel.setTargetPackage(packageName)
                                    viewModel.addLog("Aplicación de destino seleccionada: $packageName")
                                    targetMenuExpanded = false
                                }
                            )
                        }
                        if (installedApps.isEmpty()) {
                            DropdownMenuItem(text = { Text("No se encontraron aplicaciones") }, onClick = {})
                        }
                    }
                }

                if (targetPackage.isNotBlank()) {
                    val framework = detectedFrameworks[targetPackage]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            val isGameEngine = framework?.category?.let {
                                it in setOf("unity", "unreal", "godot", "cocos", "libgdx", "solar2d", "defold")
                            } == true
                            Text(
                                if (isGameEngine) "MOTOR DE JUEGO" else "FRAMEWORK / MOTOR DE JUEGO",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (framework == null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Analizando únicamente esta aplicación…", style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FrameworkBadge(framework)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(framework.name, style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                val confidence = when (framework.confidence.lowercase(Locale.ROOT)) {
                                    "alta" -> "Alta"
                                    "media" -> "Media"
                                    else -> "Baja"
                                }
                                Text("Confianza: $confidence", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Indicadores encontrados:", style = MaterialTheme.typography.labelMedium)
                                if (framework.evidence.isEmpty()) {
                                    Text("• No se encontraron indicadores concluyentes", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    framework.evidence.forEach { indicator ->
                                        Text("✓ $indicator", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Header
        TopAppBar(
            title = { Text("📝 Administrador de scripts") },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (selectedTab != 0) selectedTab = 0 else onBack()
                    }
                ) {
                    Icon(Icons.Default.ArrowBack, "Volver")
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
                text = { Text("Mis scripts", fontSize = 12.sp) },
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
                text = { Text("Registros", fontSize = 12.sp) },
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
                    selectedTargetApp = targetPackage,
                    onImportScript = {
                        viewModel.addLog("Seleccionando archivo JavaScript para importar")
                        importJsLauncher.launch(arrayOf("application/javascript", "text/javascript", "application/x-javascript", "*/*"))
                    },
                    onExportScript = { script ->
                        scriptToExport = script
                        showConfirmExport = true
                    },
                    enabledBypassScripts = enabledBypassScripts,
                    onToggleBypassScript = { id ->
                        extrasViewModel.toggleScript(id)
                        val scriptName = extrasViewModel.scripts.firstOrNull { it.id == id }?.name ?: id
                        viewModel.addLog("Script predefinido cambiado: $scriptName")
                    },
                    bypassScripts = extrasViewModel.scripts,
                    onLaunchWithBypass = { frameworkTestScript ->
                        val activeBuiltInScripts = extrasViewModel.scripts.filter { enabledBypassScripts.contains(it.id) }
                        viewModel.launchEnabledScripts(context, targetPackage, activeBuiltInScripts, frameworkTestScript)
                    },
                    frameworkInfo = detectedFrameworks[targetPackage],
                    frameworkTestEnabled = frameworkTestEnabled,
                    onToggleFrameworkTest = { frameworkTestEnabled = it },
                    bypassLoading = bypassLoading || isExecuting,
                    isRootAvailable = isRootAvailable,
                    onToggleUserScript = { script, enabled -> viewModel.setUserScriptEnabled(script.id, enabled) },
                    fridaInjectReady = fridaInjectReady,
                    onSelectScript = { script ->
                        viewModel.selectScript(script)
                        selectedTab = 1
                    },
                    onLog = { viewModel.addLog(it) },
                    onNewScript = {
                        viewModel.addLog("Nuevo script abierto en el editor")
                        viewModel.createNewScript()
                        selectedTab = 1
                    },
                    onDeleteScript = { script ->
                        scriptToDelete = script
                        showConfirmDelete = true
                    }
                )
                1 -> EditorTab(
                    selectedScript = selectedScript,
                    editorCode = editorCode,
                    onCodeChange = { viewModel.updateEditorCode(it) },
                    onSave = { name ->
                        viewModel.saveCurrentScript(name)
                        viewModel.clearMessage()
                    }
                )
                2 -> LogsTab(
                    logs = (logs + bypassLogs).takeLast(500),
                    onClearLogs = {
                        viewModel.clearLogs()
                        extrasViewModel.clearLogs()
                    },
                    onExportLogs = {
                        viewModel.exportLogs(
                            context = context,
                            logLines = (logs + bypassLogs).takeLast(500),
                            packageName = targetPackage
                        )
                    }
                )
            }
        }
    }
    
    if (showConfirmExport && scriptToExport != null) {
        val exportName = scriptToExport!!.name.let {
            if (it.endsWith(".js", ignoreCase = true)) it else "$it.js"
        }
        AlertDialog(
            onDismissRequest = { showConfirmExport = false },
            title = { Text("Exportar script") },
            text = { Text("¿Deseas exportar el archivo \"$exportName\" a la carpeta de Descargas?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.exportScriptToDownloads(context, scriptToExport!!)
                    showConfirmExport = false
                }) { Text("Exportar") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmExport = false }) { Text("Cancelar") }
            }
        )
    }

    // Dialog de confirmación de eliminación
    if (showConfirmDelete && scriptToDelete != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Eliminar script") },
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
private fun FrameworkBadge(info: FrameworkInfo) {
    val color = when (info.category) {
        "unity" -> MaterialTheme.colorScheme.primary
        "unreal" -> MaterialTheme.colorScheme.tertiary
        "godot" -> MaterialTheme.colorScheme.error
        "flutter" -> MaterialTheme.colorScheme.secondary
        "react" -> MaterialTheme.colorScheme.primary
        "javascript" -> MaterialTheme.colorScheme.tertiary
        "dotnet" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = color,
        contentColor = if (info.category == "native") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.size(29.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = info.badge,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                fontSize = if (info.badge.length > 1) 9.sp else 12.sp
            )
        }
    }
}

@Composable
private fun AppPackageIcon(packageName: String) {
    val context = LocalContext.current
    val iconBitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = "Icono de $packageName",
            modifier = Modifier.size(32.dp)
        )
    } else {
        Icon(Icons.Default.Android, contentDescription = "Aplicación", modifier = Modifier.size(32.dp))
    }
}


@Composable
fun ScriptListTab(
    scripts: List<FridaScript>,
    selectedScript: FridaScript?,
    selectedTargetApp: String,
    onImportScript: () -> Unit,
    onExportScript: (FridaScript) -> Unit,
    onLog: (String) -> Unit,
    enabledBypassScripts: Set<String>,
    onToggleBypassScript: (String) -> Unit,
    bypassScripts: List<ScriptUtils.BypassScript>,
    onLaunchWithBypass: (FridaScript?) -> Unit,
    frameworkInfo: FrameworkInfo?,
    frameworkTestEnabled: Boolean,
    onToggleFrameworkTest: (Boolean) -> Unit,
    bypassLoading: Boolean,
    isRootAvailable: Boolean,
    onToggleUserScript: (FridaScript, Boolean) -> Unit,
    fridaInjectReady: Boolean,
    onSelectScript: (FridaScript) -> Unit,
    onNewScript: () -> Unit,
    onDeleteScript: (FridaScript) -> Unit
 ) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
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
        if (frameworkInfo != null && selectedTargetApp.isNotBlank()) {
            item {
                val testCode = remember(frameworkInfo.name, frameworkInfo.category) {
                    FrameworkTestScript.build(frameworkInfo.name, frameworkInfo.category)
                }
                var showTestSource by remember(selectedTargetApp, frameworkInfo.name) { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "🧪 Script Test By Tinchodoko — ${frameworkInfo.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Muestra “Hola Mundo” y registra solo el primer toque Android en los logs.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = frameworkTestEnabled,
                                onCheckedChange = onToggleFrameworkTest,
                                enabled = !bypassLoading
                            )
                        }
                        TextButton(onClick = { showTestSource = !showTestSource }) {
                            Text(if (showTestSource) "Ocultar código" else "Ver código de prueba")
                        }
                        if (showTestSource) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                LazyColumn(modifier = Modifier.padding(8.dp)) {
                                    items(testCode.lines()) { line ->
                                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                onClick = {
                    val testScript = if (frameworkTestEnabled && frameworkInfo != null) {
                        FridaScript(
                            name = "Script Test By Tinchodoko ${frameworkInfo.name}",
                            code = FrameworkTestScript.build(frameworkInfo.name, frameworkInfo.category),
                            description = "Prueba de banner y primer toque",
                            enabledForLaunch = true
                        )
                    } else null
                    onLaunchWithBypass(testScript)
                },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                enabled = isRootAvailable && !bypassLoading && fridaInjectReady && selectedTargetApp.isNotBlank() &&
                (enabledBypassScripts.isNotEmpty() || scripts.any { it.enabledForLaunch } || (frameworkTestEnabled && frameworkInfo != null))
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
    onClearLogs: () -> Unit,
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
        
        // Solo acciones propias del registro: limpiar y exportar.
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
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
