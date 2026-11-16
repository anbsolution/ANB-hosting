package com.anbhosting.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStaticServerTest {
    private lateinit var context: Context
    private lateinit var store: LocalHostingStore
    private var server: LocalStaticServer? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("anb-local-hosting.db")
        context.filesDir.resolve("local-sites").deleteRecursively()
        store = LocalHostingStore(context)
    }

    @After
    fun tearDown() {
        server?.stop()
        store.close()
    }

    @Test
    fun servesActiveSiteFromAppPrivateStorage() {
        val site = store.createSite("Offline demo")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/?secret=must-not-be-logged")
        val headResponse = request("/s/${site.slug}/", method = "HEAD")

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(headResponse.startsWith("HTTP/1.1 200 OK"))
        assertTrue(headResponse.substringAfter("\r\n\r\n").isEmpty())
        assertTrue(response.contains("Offline demo"))
        assertTrue(response.contains("This starter page is ready for local preview."))
        val accessLog = store.recentEvents().first { it.action == "request.served" && it.detail.startsWith("GET ") }
        assertEquals("GET /s/${site.slug}/ → 200", accessLog.detail)
        assertFalse(accessLog.detail.contains("must-not-be-logged"))
        assertEquals(site.root.resolve("index.html").length(), store.trafficLast30Days(site.id))
    }

    @Test
    fun rejectsTraversalOutsideSiteRoot() {
        val site = store.createSite("Offline demo")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/%2e%2e/anb-local-hosting.db")

        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"))
        assertTrue(store.recentEvents().any { it.action == "request.error" && it.detail.endsWith("403") })
    }

    @Test
    fun doesNotServeStoppedSites() {
        val site = store.createSite("Offline demo")
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/")
        val headResponse = request("/s/${site.slug}/", method = "HEAD")

        assertTrue(response.startsWith("HTTP/1.1 404 Not Found"))
        assertTrue(headResponse.startsWith("HTTP/1.1 404 Not Found"))
        assertTrue(headResponse.substringAfter("\r\n\r\n").isEmpty())
        assertTrue(store.recentEvents().any { it.action == "request.error" && it.detail.endsWith("404") })
        assertEquals(0L, store.trafficLast30Days(site.id))
    }

    private fun request(path: String, method: String = "GET"): String = Socket("127.0.0.1", LocalStaticServer.PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
