package com.anbhosting.app

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalWebsitePreviewTest {
    @Test
    fun webViewLoadsOfflineLocalhostSiteAndReportsJavaScriptConsoleErrors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = LocalHostingStore(context)
        val site = store.createSite("Preview webview")
        site.root.resolve("index.html").writeText(
            """<!doctype html><html><head><title>Local preview</title></head>
                <body><h1>OFFLINE_PREVIEW_CONTENT</h1><script>console.error('preview-console-probe')</script></body>
            </html>""".trimIndent(),
        )
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        val server = LocalStaticServer(context, store)
        val pageLoaded = CountDownLatch(1)
        val consoleErrorCaptured = CountDownLatch(1)
        var bodyContent = ""
        var loadFailure: String? = null
        var webView: WebView? = null

        try {
            server.start(allowLan = false)
            instrumentation.runOnMainSync {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("document.body.innerText") { value ->
                                bodyContent = value.orEmpty()
                                pageLoaded.countDown()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                loadFailure = error.description?.toString() ?: "WebView load failed"
                                pageLoaded.countDown()
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.message().contains("preview-console-probe")) {
                                consoleErrorCaptured.countDown()
                            }
                            return true
                        }
                    }
                    loadUrl("http://${site.localHostname}:${LocalStaticServer.PORT}/")
                }
            }

            assertTrue("The offline .localhost preview did not finish loading", pageLoaded.await(15, TimeUnit.SECONDS))
            assertNull("The offline preview returned a WebView load error", loadFailure)
            assertTrue("The local website body was not rendered", bodyContent.contains("OFFLINE_PREVIEW_CONTENT"))
            assertTrue(
                "The website JavaScript console error was not reported",
                consoleErrorCaptured.await(5, TimeUnit.SECONDS),
            )
        } finally {
            instrumentation.runOnMainSync {
                webView?.stopLoading()
                webView?.destroy()
            }
            server.stop()
            store.deleteSite(site.id)
            store.close()
        }
    }
}
