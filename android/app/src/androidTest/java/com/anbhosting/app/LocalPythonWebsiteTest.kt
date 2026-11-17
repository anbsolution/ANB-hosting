package com.anbhosting.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class LocalPythonWebsiteTest {
    @Test
    fun localHttpRequestsExecutePythonHandlerInAnIsolatedWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalHostingStore(context)
        val site = store.createSite("Python offline", LocalHostingStore.RUNTIME_PYTHON)
        assertEquals(LocalHostingStore.RUNTIME_PYTHON, store.findSite(site.id)?.runtime)
        assertTrue(site.root.resolve("main.py").isFile)
        site.root.resolve("main.py").writeText(
            "def handle_request(request):\n" +
                "    return {\n" +
                "        'status': 200,\n" +
                "        'content_type': 'text/plain; charset=utf-8',\n" +
                "        'body': 'python ' + request['method'] + ' ' + request['path'] + ' ' + request['query'],\n" +
                "    }\n",
        )
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        val server = LocalStaticServer(context, store)
        val expectedGetBody = "python GET /profile mode=offline"
        val expectedHeadBody = "python HEAD /profile mode=offline"

        try {
            server.start(allowLan = false)
            val response = request(site.localHostname, "GET", "/profile?mode=offline")
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertEquals(expectedGetBody, response.substringAfter("\r\n\r\n"))

            val head = request(site.localHostname, "HEAD", "/profile?mode=offline")
            assertTrue(head.startsWith("HTTP/1.1 200 OK"))
            assertTrue(head.contains("Content-Length: ${expectedHeadBody.toByteArray().size}\r\n"))
            assertEquals("", head.substringAfter("\r\n\r\n"))
            assertEquals(2, store.httpRequestLogs(site.id).size)
            assertEquals(expectedGetBody.toByteArray().size.toLong(), store.trafficLast30Days(site.id))
        } finally {
            server.stop()
            store.deleteSite(site.id)
            store.close()
        }
    }

    @Test
    fun stoppedPythonSiteReleasesItsIsolatedWorkerSlot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalHostingStore(context)
        val sites = listOf("Python one", "Python two", "Python three")
            .map { store.createSite(it, LocalHostingStore.RUNTIME_PYTHON) }
        sites.take(2).forEach { store.setSiteStatus(it.id, LocalHostingStore.STATUS_ACTIVE) }
        val server = LocalStaticServer(context, store)

        try {
            server.start(allowLan = false)
            sites.take(2).forEach { site ->
                assertTrue(request(site.localHostname, "GET", "/").startsWith("HTTP/1.1 200 OK"))
            }

            store.setSiteStatus(sites[0].id, LocalHostingStore.STATUS_STOPPED)
            store.setSiteStatus(sites[2].id, LocalHostingStore.STATUS_ACTIVE)

            assertTrue(request(sites[2].localHostname, "GET", "/").startsWith("HTTP/1.1 200 OK"))
        } finally {
            server.stop()
            sites.forEach { store.deleteSite(it.id) }
            store.close()
        }
    }

    private fun request(hostname: String, method: String, path: String): String =
        Socket("127.0.0.1", LocalStaticServer.PORT).use { socket ->
            socket.soTimeout = 25_000
            socket.getOutputStream().write(
                (
                    "$method $path HTTP/1.1\r\n" +
                        "Host: $hostname:${LocalStaticServer.PORT}\r\n" +
                        "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
}
