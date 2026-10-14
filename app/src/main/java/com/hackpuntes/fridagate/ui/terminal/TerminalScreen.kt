package com.hackpuntes.fridagate.ui.terminal

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TerminalBg = Color(0xFF080B09)
private val TerminalPanel = Color(0xFF101712)
private val TerminalGreen = Color(0xFF78E08F)
private val TerminalText = Color(0xFFD6E3D8)
private val TerminalMuted = Color(0xFF8CA092)

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lines = remember {
        mutableStateListOf(
            "FridaGate 2.0 — Terminal integrada",
            "Comandos ejecutados con el usuario de la app por defecto.",
            "Usa el menú ⋮ para activar ejecución root cuando la necesites.",
            ""
        )
    }
    val history = remember { mutableStateListOf<String>() }
    var command by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var rootEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var currentDirectory by remember { mutableStateOf("/sdcard") }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun appendOutput(text: String) {
        text.lineSequence().forEach { lines.add(it) }
        while (lines.size > 4000) lines.removeAt(0)
    }

    suspend fun executeCommand(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || busy) return
        history.add(trimmed)
        while (history.size > 300) history.removeAt(0)
        historyIndex = -1
        appendOutput("$ $trimmed")
        command = ""
        if (trimmed == "clear" || trimmed == "cls") {
            lines.clear()
            return
        }
        if (trimmed == "exit") {
            appendOutput("La terminal integrada permanece abierta.")
            return
        }
        if (trimmed.startsWith("cd ") || trimmed == "cd") {
            val target = trimmed.removePrefix("cd").trim().ifEmpty { "/sdcard" }
            val candidate = if (target.startsWith("/")) File(target) else File(currentDirectory, target)
            if (candidate.isDirectory) {
                currentDirectory = candidate.canonicalPath
            } else {
                appendOutput("cd: no es un directorio: $target")
            }
            return
        }

        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                val safeDirectory = "'" + currentDirectory.replace("'", "'\\''") + "'"
                val shellCommand = "cd $safeDirectory && $trimmed"
                val process = if (rootEnabled) {
                    ProcessBuilder("su", "-c", shellCommand)
                } else {
                    ProcessBuilder("/system/bin/sh", "-c", shellCommand)
                }
                process.redirectErrorStream(true)
                val proc = process.start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()
                Pair(output, exitCode)
            }
            if (result.first.isNotBlank()) appendOutput(result.first.trimEnd())
            appendOutput("[código de salida: ${result.second}]")
        } catch (e: Exception) {
            appendOutput("Error: ${e.message ?: "no se pudo ejecutar el comando"}")
        } finally {
            busy = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = TerminalBg) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = TerminalGreen)
                    Text("TERMINAL", color = TerminalGreen, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = TerminalGreen)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menú de terminal", tint = TerminalText)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (rootEnabled) "Desactivar ejecución root" else "Activar ejecución root (su)") },
                            onClick = { rootEnabled = !rootEnabled; menuExpanded = false }
                        )
                        DropdownMenuItem(text = { Text("Limpiar terminal") }, onClick = { menuExpanded = false; showClearConfirm = true })
                        DropdownMenuItem(text = { Text("Mostrar directorio actual") }, onClick = {
                            appendOutput(currentDirectory); menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Ayuda rápida") }, onClick = {
                            appendOutput("Comandos: pwd, ls, cat, getprop, pm, am, cd, clear. Root: su (opcional desde menú).")
                            menuExpanded = false
                        })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(TerminalPanel, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(currentDirectory, color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(if (rootEnabled) "ROOT ACTIVADO" else "USUARIO APP", color = if (rootEnabled) Color(0xFFFFC857) else TerminalGreen, fontSize = 10.sp)
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().background(TerminalBg, RoundedCornerShape(8.dp)).padding(10.dp),
                state = listState
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = when {
                            line.startsWith("$ ") -> TerminalGreen
                            line.startsWith("Error:") || line.startsWith("cd:") -> Color(0xFFFF8585)
                            line.startsWith("[código de salida: 0]") -> TerminalMuted
                            else -> TerminalText
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(TerminalPanel, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$ ", color = TerminalGreen, fontFamily = FontFamily.Monospace)
                BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TerminalGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { scope.launch { executeCommand(command) } }),
                    decorationBox = { inner ->
                        Box {
                            if (command.isEmpty()) Text("Escribe un comando…", color = TerminalMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            inner()
                        }
                    }
                )
                TextButton(enabled = !busy, onClick = { scope.launch { executeCommand(command) } }) {
                    Text("ENTER", color = TerminalGreen, fontSize = 11.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("↑", "↓", "←", "→", "CTRL", "ALT", "TAB", "ESC").forEach { key ->
                    Surface(
                        onClick = {
                            when (key) {
                                "↑" -> if (history.isNotEmpty()) {
                                    historyIndex = (if (historyIndex < 0) history.lastIndex else (historyIndex - 1).coerceAtLeast(0))
                                    command = history[historyIndex]
                                }
                                "↓" -> if (historyIndex >= 0) {
                                    historyIndex++
                                    if (historyIndex >= history.size) { historyIndex = -1; command = "" } else command = history[historyIndex]
                                }
                                "TAB" -> command += "    "
                                "ESC" -> command = ""
                                "CTRL" -> appendOutput("CTRL: los procesos se ejecutan por comando; las señales interactivas aún no están disponibles.")
                                "ALT" -> appendOutput("ALT: modificador reservado para una futura entrada de terminal interactiva.")
                                "←", "→" -> { /* El cursor se controla con el teclado del sistema. */ }
                            }
                        },
                        enabled = key != "CTRL" && key != "ALT" || !busy,
                        color = TerminalPanel,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(key, color = TerminalText, fontSize = if (key.length > 1) 9.sp else 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Button(
                onClick = { exportTerminal(context, lines.toList()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF245C38), contentColor = Color.White)
            ) {
                Text("EXPORTAR · Download/terminal.txt")
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Limpiar terminal") },
            text = { Text("¿Quieres borrar la salida visible?") },
            confirmButton = {
                TextButton(onClick = { lines.clear(); showClearConfirm = false }) { Text("LIMPIAR") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("CANCELAR") } }
        )
    }
}

private fun exportTerminal(context: Context, lines: List<String>) {
    try {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val content = "FridaGate 2.0 — Terminal exportada: $stamp\n\n" + lines.joinToString("\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "terminal.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: throw IllegalStateException("No se pudo escribir el archivo")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists() && !downloads.mkdirs()) throw IllegalStateException("No se pudo acceder a Descargas")
            File(downloads, "terminal.txt").writeText(content)
        }
        Toast.makeText(context, "Terminal exportada en Download/terminal.txt", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
