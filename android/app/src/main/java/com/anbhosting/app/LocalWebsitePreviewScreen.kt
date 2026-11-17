package com.anbhosting.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
internal fun LocalWebsitePreviewScreen(
    url: String,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(url) { Uri.parse(url) }
    val host = uri.host.orEmpty()
    val isLocalUrl = uri.scheme == "http" && host.endsWith(".localhost", ignoreCase = true) &&
        uri.port == LocalStaticServer.PORT
    val viewRef = remember { arrayOfNulls<WebView>(1) }
    val latestOnOpenBrowser by rememberUpdatedState(onOpenBrowser)
    var pageTitle by remember(url) { mutableStateOf("") }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var progress by remember(url) { mutableIntStateOf(0) }
    var loadError by remember(url) { mutableStateOf<String?>(null) }
    var showConsole by remember(url) { mutableStateOf(false) }
    val consoleMessages = remember(url) { mutableStateListOf<String>() }

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        onDispose {
            viewRef[0]?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
            viewRef[0] = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        LocalHeroHeader(
            eyebrow = "ANB HOSTING · LOCAL PREVIEW",
            title = "Website preview",
            subtitle = "Runs from this phone's local hosting server.",
            backLabel = "Sites",
            onBack = onBack,
            actions = { LocalStatusPill("ON DEVICE", LocalGreen, Color(0xFF173D43)) },
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocalPremiumCard {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        pageTitle.ifBlank { "Live website" },
                        color = LocalNavy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        currentUrl,
                        color = LocalBlue,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { viewRef[0]?.reload() },
                            enabled = isLocalUrl,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Reload") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { latestOnOpenBrowser(currentUrl) },
                            enabled = isLocalUrl,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Open in browser") }
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Website URL", currentUrl))
                                Toast.makeText(context, "Preview URL copied", Toast.LENGTH_SHORT).show()
                            },
                            enabled = isLocalUrl,
                        ) { Text("Copy") }
                    }
                }
            }

            if (progress in 1..99 && loadError == null) {
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Live console · ${consoleMessages.size} issues",
                    color = LocalNavy,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (consoleMessages.isNotEmpty()) {
                    TextButton(onClick = { consoleMessages.clear() }) { Text("Clear") }
                }
                TextButton(onClick = { showConsole = !showConsole }) {
                    Text(if (showConsole) "Hide" else "Show")
                }
            }
            if (showConsole) {
                LocalPremiumCard {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (consoleMessages.isEmpty()) {
                            Text("No JavaScript warnings or errors captured.", color = LocalMuted, fontSize = 11.sp)
                        } else {
                            consoleMessages.forEach { message ->
                                Text(message, color = Color(0xFF9B2C2C), fontSize = 10.sp, lineHeight = 14.sp)
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White),
            ) {
                if (isLocalUrl) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                viewRef[0] = this
                                setBackgroundColor(AndroidColor.WHITE)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.setSupportMultipleWindows(false)
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    settings.safeBrowsingEnabled = true
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val target = request.url
                                        val sameLocalSite = target.scheme == "http" &&
                                            target.host.equals(host, ignoreCase = true) &&
                                            target.port == LocalStaticServer.PORT
                                        if (sameLocalSite) return false
                                        latestOnOpenBrowser(target.toString())
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, pageUrl, favicon)
                                        progress = 0
                                        loadError = null
                                        if (!pageUrl.isNullOrBlank()) currentUrl = pageUrl
                                    }

                                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                                        super.onPageFinished(view, pageUrl)
                                        progress = 100
                                        pageTitle = view.title.orEmpty()
                                        if (!pageUrl.isNullOrBlank()) currentUrl = pageUrl
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request.isForMainFrame) {
                                            loadError = error.description?.toString()
                                                ?: "The local website could not be reached."
                                            progress = 100
                                        }
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress.coerceIn(0, 100)
                                    }

                                    override fun onReceivedTitle(view: WebView, title: String?) {
                                        pageTitle = title.orEmpty()
                                    }

                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        val level = message.messageLevel()
                                        if (level == ConsoleMessage.MessageLevel.ERROR ||
                                            level == ConsoleMessage.MessageLevel.WARNING
                                        ) {
                                            val source = message.sourceId().substringAfterLast('/')
                                            consoleMessages.add(
                                                0,
                                                "${level.name} · ${message.message()} · $source:${message.lineNumber()}",
                                            )
                                            if (consoleMessages.size > 20) {
                                                consoleMessages.removeAt(consoleMessages.lastIndex)
                                            }
                                        }
                                        return true
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                    )
                    loadError?.let { message ->
                        LocalPremiumCard(Modifier.align(Alignment.Center).padding(20.dp)) {
                            Column(
                                Modifier.fillMaxWidth().padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Preview could not load", color = LocalNavy, fontWeight = FontWeight.Bold)
                                Text(message, color = LocalMuted, fontSize = 12.sp)
                                OutlinedButton(
                                    onClick = { loadError = null; viewRef[0]?.reload() },
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("Try again") }
                            }
                        }
                    }
                } else {
                    LocalPremiumCard(Modifier.align(Alignment.Center).padding(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Invalid local preview address", color = LocalNavy, fontWeight = FontWeight.Bold)
                            Text("Open a website using its .localhost address.", color = LocalMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
