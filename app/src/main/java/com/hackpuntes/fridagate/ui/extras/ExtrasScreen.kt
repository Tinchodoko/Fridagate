package com.hackpuntes.fridagate.ui.extras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.utils.ScriptUtils

@Composable
fun ExtrasScreen() {
    val context = LocalContext.current
    val viewModel: ExtrasViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ExtrasViewModel(context) as T
        }
    )

    val isLoading              by viewModel.isLoading.collectAsState()
    val isFridaRunning         by viewModel.isFridaRunning.collectAsState()
    val isFridaInjectInstalled by viewModel.isFridaInjectInstalled.collectAsState()
    val fridaInjectVersion     by viewModel.fridaInjectVersion.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Banner ─────────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = "Estado del entorno Frida",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Consulta el estado de frida-server y frida-inject.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }



        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPackageCard(
    apps: List<ExtrasViewModel.AppInfo>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val displayName = apps.firstOrNull { it.packageName == selected }?.name ?: selected

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Target App", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            ExposedDropdownMenuBox(
                expanded = expanded && enabled,
                onExpandedChange = { if (enabled) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value         = if (selected.isEmpty()) "" else if (displayName != selected) "$displayName\n$selected" else selected,
                    onValueChange = {},
                    readOnly      = true,
                    label         = { Text("Select app") },
                    placeholder   = { Text("No app selected") },
                    trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier      = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                        .fillMaxWidth(),
                    enabled       = enabled,
                    maxLines      = 2,
                    textStyle     = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    if (apps.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Loading apps...", style = MaterialTheme.typography.bodySmall) },
                            onClick = {}
                        )
                    } else {
                        apps.forEach { app ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    onSelect(app.packageName)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One card with two toggle rows (one per script) and an expandable JS viewer per script.
 */
@Composable
private fun ScriptsCard(
    scripts: List<ScriptUtils.BypassScript>,
    enabledScripts: Set<String>,
    isLoading: Boolean,
    context: android.content.Context,
    onToggle: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Scripts", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))

            scripts.forEachIndexed { index, script ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                ScriptRow(
                    script    = script,
                    enabled   = enabledScripts.contains(script.id),
                    isLoading = isLoading,
                    context   = context,
                    onToggle  = { onToggle(script.id) }
                )
            }
        }
    }
}

@Composable
private fun ScriptRow(
    script: ScriptUtils.BypassScript,
    enabled: Boolean,
    isLoading: Boolean,
    context: android.content.Context,
    onToggle: () -> Unit
) {
    var showSource by remember { mutableStateOf(false) }
    val source by remember(showSource) {
        derivedStateOf { if (showSource) ScriptUtils.readScriptContent(context, script) else "" }
    }

    Column {
        // Toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(script.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle() }, enabled = !isLoading)
        }

        // View script link
        TextButton(onClick = { showSource = !showSource }, contentPadding = PaddingValues(0.dp)) {
            Icon(
                imageVector = if (showSource) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (showSource) "Hide script" else "View script", style = MaterialTheme.typography.labelSmall)
        }

        AnimatedVisibility(visible = showSource) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 280.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(10.dp)
            ) {
                LazyColumn {
                    items(source.lines()) { line ->
                        Text(line, color = Color(0xFFFFD700), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, onClear: () -> Unit, onRefresh: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row {
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No logs yet...", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(line, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
