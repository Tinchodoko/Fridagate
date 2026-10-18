package com.hackpuntes.fridagate.ui.about

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackpuntes.fridagate.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val downloadScope = rememberCoroutineScope()
    var isDownloadingLatestApk by remember { mutableStateOf(false) }
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
                release == currentBuild -> "FRIDAGATE 2.0 ESTÁ ACTUALIZADA."
                else -> "FRIDAGATE 2.0 USA UNA BUILD MÁS NUEVA QUE LA ÚLTIMA PUBLICACIÓN."
            }
        } catch (e: Exception) {
            updateStatus = when {
                e.message?.contains("HTTP 404", ignoreCase = true) == true ->
                    "GITHUB NO ENCONTRÓ UNA PUBLICACIÓN DISPONIBLE. REVISA LA SECCIÓN RELEASES DEL REPOSITORIO O INTÉNTALO DE NUEVO MÁS TARDE."

                e.message?.contains("HTTP 401", ignoreCase = true) == true ||
                    e.message?.contains("HTTP 403", ignoreCase = true) == true ->
                    "GITHUB DENEGÓ EL ACCESO A LAS VERSIONES. REVISA LOS PERMISOS DEL SERVICIO DE ACTUALIZACIONES."

                else -> "NO SE PUDO CONSULTAR GITHUB: " + (e.message ?: "ERROR DE CONEXIÓN")
            }
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
                "HERRAMIENTA ANDROID PARA PRUEBAS AUTORIZADAS, INSTRUMENTACIÓN Y DIAGNÓSTICO",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Description
            Text(
                "FridaGate 2.0 integra la gestión del entorno de Frida, scripts JavaScript, diagnósticos de runtime, registros, una terminal y un navegador integrado para apoyar pruebas autorizadas y análisis de aplicaciones Android.",
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
                "RECONOCIMIENTO A JAVIER OLMEDO POR EL TRABAJO DEL PROYECTO ORIGINAL 🙏",
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
                Text(
                    "✅ FRIDA: INSTALACIÓN, ACTUALIZACIÓN, EJECUCIÓN Y DESINSTALACIÓN DE SERVER E INJECT",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ SCRIPTS JAVASCRIPT: CREAR, EDITAR, IMPORTAR, GUARDAR Y ELIMINAR SCRIPTS",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ INYECCIÓN: SELECCIÓN DE APLICACIÓN Y EJECUCIÓN DE SCRIPTS HABILITADOS",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ REGISTROS: ACTIVIDAD EN TIEMPO REAL, DIAGNÓSTICO DE ERRORES Y EXPORTACIÓN TXT",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ DIAGNÓSTICO DE RUNTIME: AGENTES JAVA Y UNITY/IL2CPP INCLUIDOS EN LA BUILD",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ ICONO FLOTANTE: ACCESO RÁPIDO PARA ALTERNAR ENTRE FRIDAGATE 2.0 Y LA APLICACIÓN OBJETIVO",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ NAVEGADOR INTEGRADO CON OPCIÓN PARA ABRIR LA PÁGINA EN EL NAVEGADOR EXTERNO",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "✅ TERMINAL INTEGRADA: EJECUCIÓN DE COMANDOS, HISTORIAL Y EXPORTACIÓN DE SALIDA A TXT",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Links Section
            Text(
                "ENLACES",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )

            // Github Repository
            ClickableText(
                text = AnnotatedString("📍 CÓDIGO FUENTE: FRIDAGATE 2.0"),
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
                text = AnnotatedString("📍 CRÉDITO DEL PROYECTO ORIGINAL: JAVIER OLMEDO"),
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
                        color = if (latestBuild != null && latestBuild!! > currentBuild) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            if (!isDownloadingLatestApk) {
                                downloadScope.launch {
                                    isDownloadingLatestApk = true
                                    try {
                                        val (buildNumber, downloadUrl) = withContext(Dispatchers.IO) {
                                            val request = Request.Builder()
                                                .url("https://api.github.com/repos/Tinchodoko/Fridagate/releases/latest")
                                                .header("Accept", "application/vnd.github+json")
                                                .build()
                                            OkHttpClient().newCall(request).execute().use { response ->
                                                if (!response.isSuccessful) {
                                                    throw IOException("GitHub respondió HTTP " + response.code)
                                                }
                                                val json = JSONObject(
                                                    response.body?.string()
                                                        ?: throw IOException("Respuesta vacía de GitHub")
                                                )
                                                val tag = json.optString("tag_name")
                                                val build = Regex("(\\d+)").find(tag)?.groupValues?.get(1)
                                                    ?: throw IOException("La publicación no contiene un número de build válido")
                                                val assets = json.optJSONArray("assets")
                                                    ?: throw IOException("La publicación no contiene archivos descargables")
                                                var apkUrl: String? = null
                                                for (index in 0 until assets.length()) {
                                                    val asset = assets.optJSONObject(index) ?: continue
                                                    if (asset.optString("name") == "app-debug.apk") {
                                                        apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                                                        break
                                                    }
                                                }
                                                build to (apkUrl
                                                    ?: throw IOException("La última publicación no contiene app-debug.apk"))
                                            }
                                        }
                                        val fileName = "FridaGate2.0-Build-$buildNumber.apk"
                                        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        val downloadRequest = DownloadManager.Request(Uri.parse(downloadUrl))
                                            .setTitle(fileName)
                                            .setDescription("Descargando la última APK de FridaGate 2.0")
                                            .setMimeType("application/vnd.android.package-archive")
                                            .setNotificationVisibility(
                                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                            )
                                            .setDestinationInExternalPublicDir(
                                                Environment.DIRECTORY_DOWNLOADS,
                                                fileName
                                            )
                                            .setAllowedOverMetered(true)
                                            .setAllowedOverRoaming(true)
                                        downloadManager.enqueue(downloadRequest)
                                        Toast.makeText(
                                            context,
                                            "Descarga iniciada: Download/$fileName",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "No se pudo iniciar la descarga: " + (e.message ?: "error desconocido"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isDownloadingLatestApk = false
                                    }
                                }
                            }
                        },
                        enabled = !isDownloadingLatestApk,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isDownloadingLatestApk) "PREPARANDO DESCARGA..." else "DESCARGAR ÚLTIMA APK")
                    }
                    OutlinedButton(
                        onClick = { onOpenUrl("https://github.com/Tinchodoko/Fridagate/releases/latest") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("VER PUBLICACIÓN EN GITHUB")
                    }
                    Text(
                        "La APK se descarga directamente en la carpeta Download con el número de build en el nombre. La instalación debe confirmarse manualmente en Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
