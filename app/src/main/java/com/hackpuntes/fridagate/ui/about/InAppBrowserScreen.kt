package com.hackpuntes.fridagate.ui.about

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("ENLACES") }
    var webViewUnavailable by remember { mutableStateOf(false) }

    fun openExternal(targetUrl: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No hay un navegador externo disponible", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "No se pudo abrir el enlace", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (webViewUnavailable) "ABRIR ENLACE" else pageTitle, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = {
                    if (webView?.canGoBack() == true) webView?.goBack() else onBack()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
            },
            actions = {
                if (!webViewUnavailable) {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                    }
                }
                IconButton(onClick = { openExternal(webView?.url ?: url) }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Abrir en el navegador externo")
                }
            }
        )
        if (!webViewUnavailable && progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title?.take(32)?.ifBlank { "ENLACES" } ?: "ENLACES"
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                progress = 1
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                val target = url ?: return false
                                if (target.startsWith("http://") || target.startsWith("https://")) return false
                                openExternal(target)
                                return true
                            }
                        }
                        loadUrl(url)
                    }
                } catch (_: Exception) {
                    webViewUnavailable = true
                    openExternal(url)
                    TextView(ctx).apply {
                        text = "Android WebView no está disponible o no se pudo iniciar. Se intentó abrir el enlace en el navegador externo."
                        textSize = 16f
                        setPadding(32, 32, 32, 32)
                    }
                }
            },
            update = { view: View ->
                if (view is TextView && !webViewUnavailable) {
                    view.text = "No se pudo iniciar Android WebView."
                }
            }
        )
    }
}
