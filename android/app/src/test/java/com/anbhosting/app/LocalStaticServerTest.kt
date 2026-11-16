package com.anbhosting.app

import android.content.Context
import org.junit.After
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

        val response = request("/s/${site.slug}/")

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("Offline demo"))
        assertTrue(response.contains("This starter page is ready for local preview."))
    }

    @Test
    fun rejectsTraversalOutsideSiteRoot() {
        val site = store.createSite("Offline demo")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/%2e%2e/anb-local-hosting.db")

        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"))
    }

    @Test
    fun doesNotServeStoppedSites() {
        val site = store.createSite("Offline demo")
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/")

        assertTrue(response.startsWith("HTTP/1.1 404 Not Found"))
    }

    private fun request(path: String): String = Socket("127.0.0.1", LocalStaticServer.PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
