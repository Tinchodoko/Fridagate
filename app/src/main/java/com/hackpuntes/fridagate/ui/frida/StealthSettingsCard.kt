package com.hackpuntes.fridagate.ui.frida

// Agregar este componente en FridaScreen.kt o en un archivo nuevo StealthSettingsCard.kt
// Llamarlo desde FridaScreen pasando el viewModel.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hackpuntes.fridagate.utils.FridaStealthConfig

/**
 * Tarjeta de configuración de camuflaje.
 *
 * Colocala en FridaScreen.kt, después de la tarjeta de estado del servidor.
 *
 * Uso en FridaScreen:
 *   val stealthConfig by viewModel.stealthConfig.collectAsState()
 *   StealthSettingsCard(
 *       currentConfig = stealthConfig,
 *       onSave = { newConfig ->
 *           val error = viewModel.updateStealthConfig(newConfig)
 *           if (error != null) { /* mostrar snackbar */ }
 *       },
 *       onReset = { viewModel.resetStealthConfig() }
 *   )
 */
@Composable
fun StealthSettingsCard(
    currentConfig: FridaStealthConfig,
    onSave: (FridaStealthConfig) -> String?,  // devuelve null=ok, string=error
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Estados del formulario — inicializados desde la config actual
    var installDir by remember(currentConfig) { mutableStateOf(currentConfig.installDir) }
    var serverAlias by remember(currentConfig) { mutableStateOf(currentConfig.serverAlias) }
    var injectAlias by remember(currentConfig) { mutableStateOf(currentConfig.injectAlias) }
    var portText by remember(currentConfig) { mutableStateOf(currentConfig.port.toString()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Indicador visual de si la config está modificada respecto a la guardada
    val isDirty = installDir != currentConfig.installDir ||
        serverAlias != currentConfig.serverAlias ||
        injectAlias != currentConfig.injectAlias ||
        portText != currentConfig.port.toString()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {

            // ── Header clickeable ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            "Configuración de camuflaje",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${currentConfig.serverAlias}  ·  :${currentConfig.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Colapsar" else "Expandir"
                    )
                }
            }

            // ── Formulario (visible solo si está expandido) ───────────────
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    // Directorio de instalación
                    OutlinedTextField(
                        value = installDir,
                        onValueChange = { installDir = it; errorMsg = null },
                        label = { Text("Directorio de instalación") },
                        placeholder = { Text("/data/local/tmp") },
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        supportingText = {
                            Text("No usar /sdcard/ — tiene noexec. Recomendado: /data/local/tmp/.sys")
                        },
                        isError = installDir.startsWith("/sdcard")
                    )

                    // Alias del server
                    OutlinedTextField(
                        value = serverAlias,
                        onValueChange = { serverAlias = it; errorMsg = null },
                        label = { Text("Nombre del proceso server") },
                        placeholder = { Text("thermal_svc") },
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        supportingText = {
                            Text("Reemplaza 'frida-server' en la lista de procesos. Ej: thermal_svc, msm_health")
                        }
                    )

                    // Alias del inject
                    OutlinedTextField(
                        value = injectAlias,
                        onValueChange = { injectAlias = it; errorMsg = null },
                        label = { Text("Nombre del proceso inject") },
                        placeholder = { Text("msm_inject") },
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        supportingText = {
                            Text("Reemplaza 'frida-inject'. Debe ser distinto al del server.")
                        }
                    )

                    // Puerto
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it; errorMsg = null },
                        label = { Text("Puerto de escucha") },
                        placeholder = { Text("37285") },
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        supportingText = {
                            Text("Puerto conocido de Frida: 27042. Usá cualquier otro entre 1024-65535.")
                        },
                        isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true
                    )

                    // Presets rápidos
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Presets:",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        AssistChip(
                            onClick = {
                                installDir = FridaStealthConfig.DEFAULT.installDir
                                serverAlias = FridaStealthConfig.DEFAULT.serverAlias
                                injectAlias = FridaStealthConfig.DEFAULT.injectAlias
                                portText = FridaStealthConfig.DEFAULT.port.toString()
                            },
                            label = { Text("Original") }
                        )
                        AssistChip(
                            onClick = {
                                installDir = FridaStealthConfig.STEALTH.installDir
                                serverAlias = FridaStealthConfig.STEALTH.serverAlias
                                injectAlias = FridaStealthConfig.STEALTH.injectAlias
                                portText = FridaStealthConfig.STEALTH.port.toString()
                            },
                            label = { Text("Stealth") }
                        )
                    }

                    // Mensaje de error
                    if (errorMsg != null) {
                        Text(
                            "⚠️ $errorMsg",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Botones
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Reset
                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Resetear") }

                        // Guardar
                        Button(
                            enabled = isDirty,
                            onClick = {
                                val port = portText.toIntOrNull()
                                if (port == null) {
                                    errorMsg = "Puerto inválido — ingresá un número."
                                    return@Button
                                }
                                val newConfig = FridaStealthConfig(
                                    installDir = installDir.trim(),
                                    serverAlias = serverAlias.trim(),
                                    injectAlias = injectAlias.trim(),
                                    port = port
                                )
                                val error = onSave(newConfig)
                                if (error != null) {
                                    errorMsg = error
                                } else {
                                    errorMsg = null
                                    expanded = false  // colapsar tras guardar exitosamente
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Guardar") }
                    }

                    // Nota informativa
                    Text(
                        "ℹ️ Si el servidor está corriendo, se detendrá antes de aplicar el cambio. " +
                            "Los binarios ya instalados NO se mueven — deberás reinstalar en la nueva ruta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // Diálogo de confirmación de reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("¿Resetear configuración?") },
            text = { Text("Esto restaurará las rutas y el puerto originales de Frida (sin camuflaje). ¿Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) { Text("Resetear") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cómo integrar en FridaScreen.kt:
// ─────────────────────────────────────────────────────────────────────────────
//
// 1. En FridaScreen(), agregar:
//    val stealthConfig by viewModel.stealthConfig.collectAsState()
//    val snackbarHostState = remember { SnackbarHostState() }
//    val scope = rememberCoroutineScope()
//
// 2. Después de la tarjeta de estado del servidor, agregar:
//    StealthSettingsCard(
//        currentConfig = stealthConfig,
//        onSave = { newConfig ->
//            val error = viewModel.updateStealthConfig(newConfig)
//            if (error != null) {
//                scope.launch { snackbarHostState.showSnackbar(error) }
//            }
//            error
//        },
//        onReset = { viewModel.resetStealthConfig() }
//    )
