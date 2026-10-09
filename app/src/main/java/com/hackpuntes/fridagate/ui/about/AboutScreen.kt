package com.hackpuntes.fridagate.ui.about

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        TopAppBar(
            title = { Text("Acerca de FridaGate 2.0") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Volver")
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
                "FridaGate 2.0",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                "Herramienta de análisis de seguridad para Android",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Description
            Text(
                "FridaGate 2.0 es una herramienta integral para analizar la seguridad de aplicaciones Android. Permite gestionar scripts de Frida, consultar registros y exportar resultados directamente desde el dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // Author Section
            Text(
                "Autor",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            Text(
                "Tinchodoko 😈",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Text(
                "Gracias a Javier Olmedo por su trabajo y por el proyecto original 🙏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Features
            Text(
                "Funciones",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text("✅ Gestor de archivos: crear, editar y eliminar scripts .js", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ Editor avanzado: resaltado de sintaxis y plantillas", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ Ejecución: inyectar scripts en procesos mediante Frida", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ Registros: captura en tiempo real y exportación a TXT", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                Text("✅ Compatibilidad con IL2CPP y frida-il2cpp-bridge", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            
            // Links Section
            Text(
                "Enlaces",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp, top = 16.dp)
            )
            
            // Github Repository
            ClickableText(
                text = AnnotatedString("📍 Repositorio de GitHub: Tinchodoko/Fridagate"),
                onClick = {
                    uriHandler.openUri("https://github.com/Tinchodoko/Fridagate")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Owner Repository
            ClickableText(
                text = AnnotatedString("📍 Repositorio original: JavierOlmedo/Fridagate"),
                onClick = {
                    uriHandler.openUri("https://github.com/JavierOlmedo/Fridagate")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Frida Documentation
            ClickableText(
                text = AnnotatedString("📍 Sitio oficial de Frida"),
                onClick = {
                    uriHandler.openUri("https://frida.re")
                },
                style = TextStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Version
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                "FridaGate 2.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 16.dp)
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
                "Hecho en Argentina por Tinchodoko 🇦🇷",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
