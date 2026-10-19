package com.hackpuntes.fridagate.ui.frida

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val isOriginal = currentConfig == FridaStealthConfig.DEFAULT
    var installDirSuffix by remember(currentConfig) {
        mutableStateOf(
            currentConfig.installDir
                .removePrefix("/data/local/tmp/.")
                .takeIf { currentConfig.installDir.startsWith("/data/local/tmp/.") }
                ?: "Tinchodoko"
        )
    }
    var serverAlias by remember(currentConfig) { mutableStateOf(currentConfig.serverAlias) }
    var injectAlias by remember(currentConfig) { mutableStateOf(currentConfig.injectAlias) }
    var portText by remember(currentConfig) { mutableStateOf(currentConfig.port.toString()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val installDir = if (isOriginal) {
        FridaStealthConfig.DEFAULT.installDir
    } else {
        "/data/local/tmp/." + installDirSuffix.trim().trimStart('/')
    }
    val port = portText.toIntOrNull()
    val draftConfig = FridaStealthConfig(
        installDir = installDir,
        serverAlias = serverAlias.trim(),
        injectAlias = injectAlias.trim(),
        port = port ?: -1
    )
    val isDirty = draftConfig != currentConfig
    val sameProcessName = serverAlias.trim().isNotEmpty() &&
        serverAlias.trim() == injectAlias.trim()
    val knownFridaPort = port == 27042
    val invalidPort = port == null || port !in 1024..65535
    val validationError = when {
        sameProcessName -> "EL NOMBRE DEL PROCESO INJECT DEBE SER DIFERENTE AL DE SERVER"
        knownFridaPort -> "Elige otro puerto, este es el puerto conocido de FRIDA"
        invalidPort -> "Coloca un puerto desde 1024 a 65535"
        else -> draftConfig.validate()
    }
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Configuración de camuflaje",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Presets:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !isFridaInstalled,
                    onClick = {
                        val preset = FridaStealthConfig.DEFAULT
                        val error = onSave(preset)
                        if (error == null) {
                            installDirSuffix = "Tinchodoko"
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
                            installDirSuffix = "Tinchodoko"
                            serverAlias = preset.serverAlias
                            injectAlias = preset.injectAlias
                            portText = preset.port.toString()
                            errorMsg = null
                        } else errorMsg = error
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF03A9F4))
                ) { Text("STEALTH") }
            }

            if (isOriginal) {
                ReadOnlySetting("DIRECTORIO DE INSTALACIÓN", "/DATA/LOCAL/TMP", monoStyle)
                ReadOnlySetting("Nombre de proceso del server", "frida-server", monoStyle)
                ReadOnlySetting("Nombre del proceso de inject", "frida-inject", monoStyle)
                ReadOnlySetting("Puerto de escucha", "27042", monoStyle)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Directorio de instalación", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "/data/local/tmp/.",
                            style = monoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = installDirSuffix,
                            onValueChange = {
                                installDirSuffix = it.trimStart('/')
                                errorMsg = null
                            },
                            modifier = Modifier.weight(1f),
                            textStyle = monoStyle,
                            singleLine = true,
                            enabled = !isFridaInstalled,
                            isError = installDirSuffix.isBlank() ||
                                installDirSuffix.split('/').any { it == ".." },
                            supportingText = {
                                Text(
                                    "Coloca una nueva ruta de instalación. Se debe instalar en la ruta\n" +
                                        "/data/local/tmp/." + installDirSuffix.trim().trimStart('/')
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = serverAlias,
                    onValueChange = { serverAlias = it; errorMsg = null },
                    label = { Text("Nombre del proceso del server") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = monoStyle,
                    supportingText = {
                        Text("Reemplaza para cambiar el nombre del proceso FRIDA-SERVER")
                    },
                    singleLine = true,
                    enabled = !isFridaInstalled
                )

                OutlinedTextField(
                    value = injectAlias,
                    onValueChange = { injectAlias = it; errorMsg = null },
                    label = { Text("Nombre del proceso inject") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = monoStyle,
                    supportingText = {
                        if (sameProcessName) {
                            Text(
                                "EL NOMBRE DEL PROCESO INJECT DEBE SER DIFERENTE AL DE SERVER",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text("Reemplaza para cambiar el nombre del proceso FRIDA-INJECT")
                        }
                    },
                    isError = sameProcessName,
                    singleLine = true,
                    enabled = !isFridaInstalled
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it; errorMsg = null },
                    label = { Text("Puerto de escucha") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = monoStyle,
                    supportingText = {
                        when {
                            knownFridaPort -> Text(
                                "Elige otro puerto, este es el puerto conocido de FRIDA",
                                color = MaterialTheme.colorScheme.error
                            )
                            invalidPort -> Text(
                                "Coloca un puerto desde 1024 a 65535",
                                color = MaterialTheme.colorScheme.error
                            )
                            else -> Text("Coloca un puerto desde 1024 a 65535")
                        }
                    },
                    isError = knownFridaPort || invalidPort,
                    singleLine = true,
                    enabled = !isFridaInstalled
                )

                if (errorMsg != null) {
                    Text(
                        errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isDirty && !isFridaInstalled) {
                    Button(
                        onClick = {
                            if (validationError != null) {
                                errorMsg = validationError
                            } else {
                                val saveError = onSave(draftConfig)
                                if (saveError == null) {
                                    installDirSuffix = draftConfig.installDir.removePrefix("/data/local/tmp/.")
                                    serverAlias = draftConfig.serverAlias
                                    injectAlias = draftConfig.injectAlias
                                    portText = draftConfig.port.toString()
                                    errorMsg = null
                                } else {
                                    errorMsg = saveError
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = validationError == null,
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

@Composable
private fun ReadOnlySetting(label: String, value: String, style: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
