package com.hackpuntes.fridagate.ui.about

import com.hackpuntes.fridagate.BuildConfig
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val currentBuild = BuildConfig.BUILD_NUMBER.toIntOrNull() ?: 0
    var latestBuild by remember { mutableStateOf<Int?>(null) }
    var updateStatus by remember { mutableStateOf("COMPROBANDO ACTUALIZACIONES...") }
    var isCheckingUpdate by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val release = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/Tinchodoko/Fridagate/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GitHub respondió HTTP " + response.code)
                    val json = JSONObject(response.body?.string() ?: throw IOException("Respuesta vacía de GitHub"))
                    val tag = json.optString("tag_name")
                    val build = Regex("(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                        ?: throw IOException("La publicación no contiene un número de build válido")
                    build
                }
            }
            latestBuild = release
            updateStatus = when {
                release > currentBuild -> "FRIDAGATE 2.0 ESTÁ DESACTUALIZADA: HAY UNA BUILD MÁS NUEVA DISPONIBLE EN GITHUB."
                release == currentBuild -> "FRIDAGATE ESTÁ ACTUALIZADA."
                else -> "FRIDAGATE 2.0 USA UNA BUILD MÁS NUEVA QUE LA ÚLTIMA PUBLICACIÓN."
            }
        } catch (e: Exception) {
            updateStatus = "NO SE PUDO CONSULTAR GITHUB: " + (e.message ?: "ERROR DE CONEXIÓN")
        } finally {
            isCheckingUpdate = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        TopAppBar(
            title = { Text("ACERCA DE FRIDAGATE 2.0") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "VOLVER")
                }
            }
        )
        
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Title
            Text(
                "FRIDAGATE 2.0",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                "HERRAMIENTA DE ANÁLISIS DE SEGURIDAD PARA ANDROID",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Description
            Text(
                "FRIDAGATE 2.0 es una herramienta integral para analizar la seguridad de aplicaciones Android. Permite gestionar scripts de Frida, consultar registros y exportar resultados directamente desde el dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // Author Section
            Text(
                "AUTOR",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            Text(
                "TINCHODOKO 😈",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Text(
                "GRACIAS A JAVIER OLMEDO POR SU TRABAJO Y POR EL PROYECTO ORIGINAL 🙏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Features
            Text(
                "FUNCIONES",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text("✅ FRIDA 17.6.0: GESTIÓN DE FRIDA-SERVER Y FRIDA-INJECT CON VERSIONES COINCIDENTES", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ SCRIPTS JAVASCRIPT: CREAR, EDITAR, IMPORTAR, GUARDAR Y ELIMINAR SCRIPTS", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ INYECCIÓN: SELECCIÓN DE APLICACIÓN Y EJECUCIÓN DE SCRIPTS HABILITADOS", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ CONSOLA: REGISTROS EN TIEMPO REAL, DIAGNÓSTICO DE ERRORES Y EXPORTACIÓN TXT", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ UNITY IL2CPP: AGENTE DE DIAGNÓSTICO CON BRIDGES JAVASCRIPT INCLUIDOS EN EL BUNDLE", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ ICONO FLOTANTE: ACCESO RÁPIDO PARA ALTERNAR ENTRE FRIDAGATE Y LA APLICACIÓN OBJETIVO", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ NAVEGADOR INTEGRADO CON OPCIÓN PARA ABRIR LA PÁGINA EN EL NAVEGADOR EXTERNO", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ ESTADO DEL SISTEMA: ROOT, FRIDA, PROXY IPTABLES, BURP Y PERMISOS DE ANDROID", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            
            // Links Section
            Text(
                "ENLACES",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            // Github Repository
            ClickableText(
                text = AnnotatedString("📍 REPOSITORIO DE GITHUB: TINCHODOKO/FRIDAGATE"),
                onClick = {
                    onOpenUrl("https://github.com/Tinchodoko/Fridagate")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Owner Repository
            ClickableText(
                text = AnnotatedString("📍 REPOSITORIO ORIGINAL: JAVIEROLMEDO/FRIDAGATE"),
                onClick = {
                    onOpenUrl("https://github.com/JavierOlmedo/Fridagate")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Frida Documentation
            ClickableText(
                text = AnnotatedString("📍 SITIO OFICIAL DE FRIDA"),
                onClick = {
                    onOpenUrl("https://frida.re")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // GitHub Actions builds
            ClickableText(
                text = AnnotatedString("📍 VER BUILDS EN GITHUB"),
                onClick = {
                    onOpenUrl("https://github.com/Tinchodoko/Fridagate/actions")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Contact and suggestions
            Text(
                "CONTACTO",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )

            OutlinedButton(
                onClick = {
                    uriHandler.openUri("mailto:tinchorotelamail@gmail.com?subject=Sugerencia%20para%20FridaGate2.0")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SUGERENCIA POR MAIL", modifier = Modifier.weight(1f))
                Icon(Icons.Default.Email, contentDescription = "CORREO ELECTRÓNICO")
            }

            OutlinedButton(
                onClick = { onOpenUrl("https://github.com/Tinchodoko/Fridagate/issues/new") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SUGERENCIA EN GITHUB", modifier = Modifier.weight(1f))
                Icon(Icons.Default.Code, contentDescription = "GITHUB")
            }

            OutlinedButton(
                onClick = { onOpenUrl("https://t.me/tinchoDKO") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("SUGERENCIA EN TELEGRAM", modifier = Modifier.weight(1f))
                Icon(Icons.Default.Send, contentDescription = "TELEGRAM")
            }

            Text(
                "SI TIENEN ALGUNA DUDA, SUGERENCIA, QUEJA O ENCONTRASTE UN BUG, NO DUDES EN COMUNICARTE CONMIGO, MUCHAS GRACIAS !🫂",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            // In-app updates
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "ACTUALIZACIONES",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("BUILD ACTUAL", style = MaterialTheme.typography.bodyMedium)
                        Text(BuildConfig.BUILD_NUMBER, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("BUILD (ÚLTIMO)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            latestBuild?.toString() ?: if (isCheckingUpdate) "Consultando..." else "No disponible",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    Text(
                        updateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (latestBuild != null && latestBuild!! > currentBuild)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Version
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                "FRIDAGATE 2.0 (${BuildConfig.BUILD_NUMBER})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        
        // Footer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "HECHO EN ARGENTINA POR TINCHODOKO 🇦🇷",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
