package com.hackpuntes.fridagate.ui.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackpuntes.fridagate.utils.RootUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ProxyScreen - The Proxy tab UI.
 *
 * This screen lets the user configure and control traffic interception via Burp Suite.
 *
 * Key Compose concept used here: ViewModelProvider.Factory
 *
 * ProxyViewModel needs a Context to initialize AppPreferences (DataStore).
 * But Compose's viewModel() function by default calls the ViewModel's no-arg constructor.
 * Since ProxyViewModel requires a Context parameter, we must provide a Factory —
 * a custom object that tells the framework HOW to create the ViewModel.
 */
@Composable
fun ProxyScreen() {
    val context = LocalContext.current

    // Custom factory that passes the context when creating ProxyViewModel
    // This is the standard pattern for ViewModels that need constructor parameters
    val viewModel: ProxyViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ProxyViewModel(context) as T
            }
        }
    )

    var isRootAvailable by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) CoroutineScope(Dispatchers.IO).launch { isRootAvailable = RootUtils.isRootAvailable() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect state from the ViewModel
    val burpIp by viewModel.burpIp.collectAsState()
    val httpPort by viewModel.httpPort.collectAsState()
    val httpsPort by viewModel.httpsPort.collectAsState()
    val isIptablesEnabled by viewModel.isIptablesEnabled.collectAsState()
    val isSystemProxyEnabled by viewModel.isSystemProxyEnabled.collectAsState()
    val isBurpReachable by viewModel.isBurpReachable.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Section: Burp Connection Settings ─────────────────────────────
            ConnectionSettingsCard(
                ip = burpIp,
                httpPort = httpPort,
                httpsPort = httpsPort,
                isBurpReachable = isBurpReachable,
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onIpChange = { viewModel.updateBurpIp(it) },
                onHttpPortChange = { viewModel.updateHttpPort(it) },
                onHttpsPortChange = { viewModel.updateHttpsPort(it) },
                onTestConnection = { viewModel.testBurpConnection() }
            )

            // ── Section: Proxy Methods ────────────────────────────────────────
            ProxyMethodsCard(
                isIptablesEnabled = isIptablesEnabled,
                isSystemProxyEnabled = isSystemProxyEnabled,
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onToggleIptables = { viewModel.toggleIptablesProxy(it) },
                onToggleSystemProxy = { viewModel.toggleSystemProxy(it) }
            )

            // ── Section: Certificate ──────────────────────────────────────────
            CertificateCard(
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onInstallCert = { viewModel.installBurpCertificate() }
            )

            // ── Section: Log ──────────────────────────────────────────────────
            ProxyLogPanel(
                logs = logs,
                onClear = { viewModel.clearLogs() },
                onRefresh = { viewModel.checkProxyStatus() },
                onExport = { exportProxyLog(context, logs) },
                isRootAvailable = isRootAvailable
            )
        }

        // Loading overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Card for entering Burp Suite's IP and ports, and testing connectivity.
 *
 * KeyboardType.Number tells the soft keyboard to show a numeric keypad
 * for the port fields instead of the full QWERTY keyboard.
 */
@Composable
private fun ConnectionSettingsCard(
    ip: String,
    httpPort: Int,
    httpsPort: Int,
    isBurpReachable: Boolean?,
    isLoading: Boolean,
    isRootAvailable: Boolean,
    onIpChange: (String) -> Unit,
    onHttpPortChange: (String) -> Unit,
    onHttpsPortChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "CONEXIÓN CON BURP SUITE",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // IP address field
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("HECHO POR TINCHODOKO") },
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
                singleLine = true,
                // Use number keyboard with decimal point for IP addresses
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            // Ports side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = httpPort.toString(),
                    onValueChange = onHttpPortChange,
                    label = { Text("HECHO POR TINCHODOKO") },
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = httpsPort.toString(),
                    onValueChange = onHttpsPortChange,
                    label = { Text("HECHO POR TINCHODOKO") },
                    modifier = Modifier.weight(1f),
                    enabled = false,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Connection status indicator + test button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Show the result of the last connection test
                // null = not tested yet, true = reachable, false = unreachable
                val (statusText, statusColor) = when (isBurpReachable) {
                    null  -> "Not tested" to Color.Gray
                    true  -> "● Burp reachable" to Color(0xFF4CAF50)
                    false -> "● Burp unreachable" to Color(0xFFF44336)
                }
                Text(text = statusText, color = statusColor, fontWeight = FontWeight.Medium)

                Button(
                    onClick = onTestConnection,
                    enabled = false
                ) { Text("PROBAR CONEXIÓN") }
            }
        }
    }
}

/**
 * Card with toggle switches for enabling/disabling each proxy method.
 *
 * Switch is Compose's toggle component (like a Toggle/Switch in other frameworks).
 * We use "checked" for current state and "onCheckedChange" for user interaction.
 */
@Composable
private fun ProxyMethodsCard(
    isIptablesEnabled: Boolean,
    isSystemProxyEnabled: Boolean,
    isLoading: Boolean,
    isRootAvailable: Boolean,
    onToggleIptables: (Boolean) -> Unit,
    onToggleSystemProxy: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "MÉTODOS DE PROXY",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))

            // iptables switch — recommended method, captures all apps
            ProxyToggleRow(
                title = "iptables Transparent Proxy",
                subtitle = "Redirects ALL traffic (recommended, requires root)",
                checked = isIptablesEnabled,
                enabled = false,
                onCheckedChange = onToggleIptables
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // System proxy switch — simpler but apps can bypass it
            ProxyToggleRow(
                title = "System Proxy",
                subtitle = "Only apps that respect proxy settings",
                checked = isSystemProxyEnabled,
                enabled = false,
                onCheckedChange = onToggleSystemProxy
            )
        }
    }
}

/**
 * A single toggle row with title, subtitle, and a Switch.
 */
@Composable
private fun ProxyToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Text description (takes all space except the switch)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * Card for installing Burp's CA certificate.
 * Explains why it's needed so the user understands what they're doing.
 */
@Composable
private fun CertificateCard(
    isLoading: Boolean,
    isRootAvailable: Boolean,
    onInstallCert: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "CERTIFICADO SSL",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Instala el certificado CA de Burp para interceptar tráfico HTTPS. " +
                        "Requires root and an active system proxy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onInstallCert,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable
            ) {
                Text("INSTALAR CERTIFICADO CA DE BURP")
            }
        }
    }
}

/**
 * Log panel for the proxy screen — identical in concept to the Frida log panel.
 * Has an extra Refresh button to re-check proxy status.
 */
@Composable
private fun ProxyLogPanel(
    logs: List<String>,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    isRootAvailable: Boolean
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REGISTRO",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    TextButton(onClick = onExport) { Text("EXPORTAR") }
                    TextButton(onClick = onClear) { Text("LIMPIAR") }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("Todavía no hay registros...", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                color = Color(0xFF00FF00),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun exportProxyLog(context: android.content.Context, logs: List<String>) {
    try {
        val directory = File("/sdcard/download")
        if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("No se pudo crear /sdcard/download")
        val stamp = SimpleDateFormat("MM-dd-HH-mm", Locale.US).format(Date())
        val file = File(directory, "proxy_log_(${stamp}).txt")
        file.writeText(logs.joinToString("\n"))
        Toast.makeText(context, "LOG EXPORTADO: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "ERROR AL EXPORTAR LOG: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
