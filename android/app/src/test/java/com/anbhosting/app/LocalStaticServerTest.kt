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
    fun servesActiveSiteFromItsPhoneLocalSubdomain() {
        val site = store.createSite("Offline subdomain")
        site.root.resolve("about").mkdirs()
        site.root.resolve("about/index.html").writeText("site-local-route")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/about/", host = "${site.localHostname}:${LocalStaticServer.PORT}")
        val headResponse = request(
            path = "/about/",
            method = "HEAD",
            host = site.localHostname,
        )

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.endsWith("site-local-route"))
        assertTrue(headResponse.startsWith("HTTP/1.1 200 OK"))
        assertTrue(headResponse.substringAfter("\r\n\r\n").isEmpty())
        assertTrue(store.recentEvents().any {
            it.action == "request.served" && it.detail == "GET /about/ → 200"
        })
        assertEquals("site-local-route".toByteArray().size.toLong(), store.trafficLast30Days(site.id))
    }

    @Test
    fun servesPhoneLocalSubdomainOverIpv6Loopback() {
        val site = store.createSite("IPv6 local subdomain")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request(
            path = "/",
            host = "${site.localHostname}:${LocalStaticServer.PORT}",
            address = "::1",
        )

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("IPv6 local subdomain"))
    }

    @Test
    fun localSubdomainCannotSelectUnknownOrStoppedSites() {
        val stopped = store.createSite("Stopped subdomain")
        val serverSite = store.createSite("Active subdomain")
        store.setSiteStatus(serverSite.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val stoppedResponse = request("/", host = "${stopped.localHostname}:${LocalStaticServer.PORT}")
        val unknownResponse = request("/", host = "not-a-site.localhost:${LocalStaticServer.PORT}")
        val lookalikeResponse = request("/", host = "${serverSite.slug}.localhost.attacker.example")

        assertTrue(stoppedResponse.startsWith("HTTP/1.1 404 Not Found"))
        assertTrue(unknownResponse.startsWith("HTTP/1.1 404 Not Found"))
        assertTrue(lookalikeResponse.startsWith("HTTP/1.1 404 Not Found"))
    }

    @Test
    fun localSubdomainKeepsPathTraversalAndDotfileProtections() {
        val site = store.createSite("Protected subdomain")
        site.root.resolve(".env").writeText("SECRET=value")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val traversal = request("/%2e%2e/anb-local-hosting.db", host = site.localHostname)
        val dotfile = request("/.env", host = site.localHostname)

        assertTrue(traversal.startsWith("HTTP/1.1 403 Forbidden"))
        assertTrue(dotfile.startsWith("HTTP/1.1 403 Forbidden"))
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
    fun doesNotExposeDotfilesButAllowsWellKnownFiles() {
        val site = store.createSite("Offline demo")
        site.root.resolve(".env").writeText("SECRET=value")
        site.root.resolve(".well-known").mkdirs()
        site.root.resolve(".well-known/assetlinks.json").writeText("[]")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val secretResponse = request("/s/${site.slug}/.env")
        val wellKnownResponse = request("/s/${site.slug}/.well-known/assetlinks.json")

        assertTrue(secretResponse.startsWith("HTTP/1.1 403 Forbidden"))
        assertTrue(wellKnownResponse.startsWith("HTTP/1.1 200 OK"))
        assertTrue(wellKnownResponse.endsWith("[]"))
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

    private fun request(
        path: String,
        method: String = "GET",
        host: String = "127.0.0.1",
        address: String = "127.0.0.1",
    ): String = Socket(address, LocalStaticServer.PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
