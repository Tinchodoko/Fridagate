package com.hackpuntes.fridagate.ui.frida

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hackpuntes.fridagate.utils.FridaStealthConfig

@Composable
fun StealthSettingsCard(
    currentConfig: FridaStealthConfig,
    onSave: (FridaStealthConfig) -> String?,
    onReset: () -> Unit,
    isFridaInstalled: Boolean
) {
    var expanded by remember { mutableStateOf(true) }

    var installDir by remember(currentConfig) { mutableStateOf(currentConfig.installDir) }
    var serverAlias by remember(currentConfig) { mutableStateOf(currentConfig.serverAlias) }
    var injectAlias by remember(currentConfig) { mutableStateOf(currentConfig.injectAlias) }
    var portText by remember(currentConfig) { mutableStateOf(currentConfig.port.toString()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val isDirty = installDir != currentConfig.installDir ||
        serverAlias != currentConfig.serverAlias ||
        injectAlias != currentConfig.injectAlias ||
        portText != currentConfig.port.toString()

    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // ── Header ────────────────────────────────────────────────────────
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
                    }
                }
            }

                    // Presets: al seleccionarlos, se guardan automáticamente.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Presets:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !isFridaInstalled,
                            onClick = {
                                val preset = FridaStealthConfig.DEFAULT
                                val error = onSave(preset)
                                if (error == null) {
                                    installDir = preset.installDir
                                    serverAlias = preset.serverAlias
                                    injectAlias = preset.injectAlias
                                    portText = preset.port.toString()
                                    errorMsg = null
                                } else errorMsg = error
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) { Text("ORIGINAL") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = !isFridaInstalled,
                            onClick = {
                                val preset = FridaStealthConfig.STEALTH
                                val error = onSave(preset)
                                if (error == null) {
                                    installDir = preset.installDir
                                    serverAlias = preset.serverAlias
                                    injectAlias = preset.injectAlias
                                    portText = preset.port.toString()
                                    errorMsg = null
                                } else errorMsg = error
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03A9F4))
                        ) { Text("STEALTH") }
                    }


            // ── Formulario ────────────────────────────────────────────────────
            AnimatedVisibility(visible = true) {
                Column(
                    Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = installDir,
                        onValueChange = {
                            installDir = it
                            errorMsg = null
                        },
                        label = { Text("Directorio de instalación") },
                        placeholder = { Text("/data/local/tmp") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = monoStyle,
                        supportingText = {
                            Text("No usar /sdcard/ — tiene noexec. Recomendado: /data/local/tmp/.sys")
                        },
                        isError = installDir.startsWith("/sdcard"),
                        singleLine = true,
                        enabled = !isFridaInstalled
                    )

                    OutlinedTextField(
                        value = serverAlias,
                        onValueChange = {
                            serverAlias = it
                            errorMsg = null
                        },
                        label = { Text("Nombre del proceso server") },
                        placeholder = { Text("thermal_svc") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = monoStyle,
                        supportingText = {
                            Text("Reemplaza 'frida-server' en la lista de procesos")
                        },
                        singleLine = true,
                        enabled = !isFridaInstalled
                    )

                    OutlinedTextField(
                        value = injectAlias,
                        onValueChange = {
                            injectAlias = it
                            errorMsg = null
                        },
                        label = { Text("Nombre del proceso inject") },
                        placeholder = { Text("msm_inject") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = monoStyle,
                        supportingText = {
                            Text("Reemplaza 'frida-inject'. Debe ser distinto al del server.")
                        },
                        singleLine = true,
                        enabled = !isFridaInstalled
                    )

                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it
                            errorMsg = null
                        },
                        label = { Text("Puerto de escucha") },
                        placeholder = { Text("1996") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = monoStyle,
                        supportingText = {
                            Text("Puerto conocido de Frida: 27042. Usá cualquier otro entre 1024-65535.")
                        },
                        isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                        singleLine = true,
                        enabled = !isFridaInstalled
                    )

                    // Mensaje de error
                    if (errorMsg != null) {
                        Text(
                            "⚠️ $errorMsg",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Solo aparece cuando los valores difieren de la configuración guardada.
                    if (isDirty && !isFridaInstalled) {
                        Button(
                            onClick = {
                                val newPort = portText.toIntOrNull()
                                if (newPort == null) {
                                    errorMsg = "El puerto debe ser un número entre 1024 y 65535."
                                } else {
                                    val updatedConfig = FridaStealthConfig(
                                        installDir = installDir.trim(),
                                        serverAlias = serverAlias.trim(),
                                        injectAlias = injectAlias.trim(),
                                        port = newPort
                                    )
                                    val validationError = updatedConfig.validate()
                                    if (validationError != null) {
                                        errorMsg = validationError
                                    } else {
                                        val saveError = onSave(updatedConfig)
                                        if (saveError == null) {
                                            installDir = updatedConfig.installDir
                                            serverAlias = updatedConfig.serverAlias
                                            injectAlias = updatedConfig.injectAlias
                                            portText = updatedConfig.port.toString()
                                            errorMsg = null
                                        } else {
                                            errorMsg = saveError
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00C853),
                                contentColor = Color.White
                            )
                        ) {
                            Text("✓ GUARDAR CAMBIOS")
                        }
                    }
                }
            }
        }
    }

}
