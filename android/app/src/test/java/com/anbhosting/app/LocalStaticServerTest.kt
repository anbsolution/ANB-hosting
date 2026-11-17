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
        val requestLogs = store.httpRequestLogs(site.id)
        assertEquals(2, requestLogs.size)
        assertEquals("HEAD", requestLogs.first().method)
        assertEquals(0L, requestLogs.first().responseBytes)
        assertEquals(site.root.resolve("index.html").length(), requestLogs.last().responseBytes)
        assertFalse(requestLogs.last().path.contains("must-not-be-logged"))
    }

    @Test
    fun recordsRealPerWebsiteRequestsAndFiltersErrorsWithoutQueryStrings() {
        val site = store.createSite("Request logging")
        val unrelatedSite = store.createSite("Unrelated logging")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        request("/s/${site.slug}/?session=private-value")
        request("/s/${site.slug}/missing.html?session=private-value")

        val logs = store.httpRequestLogs(site.id)
        assertEquals(2, logs.size)
        val failure = logs.first()
        assertEquals("GET", failure.method)
        assertEquals("/s/${site.slug}/missing.html", failure.path)
        assertEquals(404, failure.statusCode)
        assertTrue(failure.isError)
        assertFalse(failure.path.contains("private-value"))
        assertEquals(1, store.httpRequestLogs(site.id, errorsOnly = true).size)
        assertTrue(store.httpRequestLogs(unrelatedSite.id).isEmpty())
        assertEquals(2L, store.httpRequestSummary(site.id).requestsLast24Hours)
        assertEquals(1L, store.httpRequestSummary(site.id).errorsLast24Hours)
        assertTrue(store.requestLogsCsv(site.id).contains("/s/${site.slug}/missing.html"))
        assertFalse(store.requestLogsCsv(site.id).contains("private-value"))
    }

    @Test
    fun requestLogCsvEscapesCellsAndProtectsSpreadsheetFormulas() {
        val site = store.createSite("CSV logging")
        store.recordHttpRequest(
            siteId = site.id,
            method = "GET",
            path = "/a,\"b\"",
            statusCode = 500,
            responseBytes = 0,
            errorMessage = "=SUM(1,2)",
        )

        val csv = store.requestLogsCsv(site.id)
        assertTrue(csv.contains("\"/a,\"\"b\"\"\""))
        assertTrue(csv.contains("\"'=SUM(1,2)\""))
    }

    @Test
    fun keepsOnlyTheNewestBoundedRequestHistoryPerWebsite() {
        val site = store.createSite("Bounded logs")
        repeat(LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE + 1) { index ->
            store.recordHttpRequest(site.id, "GET", "/$index", 200, 1, null)
        }

        val retained = store.httpRequestLogs(site.id, limit = LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE)
        assertEquals(LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE, retained.size)
        assertEquals("/${LocalHostingStore.MAX_REQUEST_LOGS_PER_SITE}", retained.first().path)
        assertEquals("/1", retained.last().path)
        store.deleteSite(site.id)
        assertTrue(store.httpRequestLogs(site.id).isEmpty())
    }

    @Test
    fun servesBoundedAndSuffixByteRangesAndCountsOnlyTransferredBytes() {
        val site = store.createSite("Ranged media")
        site.root.resolve("media.bin").writeText("0123456789")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val bounded = request("/s/${site.slug}/media.bin", range = "bytes=2-5")
        val suffix = request("/s/${site.slug}/media.bin", range = "bytes=-3")
        val openEnded = request("/s/${site.slug}/media.bin", range = "bytes=7-")

        assertTrue(bounded.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(bounded.contains("Content-Range: bytes 2-5/10\r\n"))
        assertTrue(bounded.contains("Accept-Ranges: bytes\r\n"))
        assertEquals("2345", bounded.substringAfter("\r\n\r\n"))
        assertEquals("789", suffix.substringAfter("\r\n\r\n"))
        assertEquals("789", openEnded.substringAfter("\r\n\r\n"))
        assertEquals(10L, store.trafficLast30Days(site.id))
    }

    @Test
    fun rangeHeadHasPartialHeadersWithoutBodyAndUnsatisfiableRangeReturns416() {
        val site = store.createSite("Range metadata")
        site.root.resolve("media.bin").writeText("abcdef")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val head = request("/s/${site.slug}/media.bin", method = "HEAD", range = "bytes=1-3")
        val unsatisfiable = request("/s/${site.slug}/media.bin", range = "bytes=6-")

        assertTrue(head.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(head.contains("Content-Length: 3\r\n"))
        assertTrue(head.contains("Content-Range: bytes 1-3/6\r\n"))
        assertTrue(head.substringAfter("\r\n\r\n").isEmpty())
        assertTrue(unsatisfiable.startsWith("HTTP/1.1 416 Range Not Satisfiable"))
        assertTrue(unsatisfiable.contains("Content-Range: bytes */6\r\n"))
        assertEquals(0L, store.trafficLast30Days(site.id))
    }

    @Test
    fun malformedAndMultipleRangesFallBackToTheFullFile() {
        val site = store.createSite("Invalid range")
        site.root.resolve("media.bin").writeText("abcdef")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/s/${site.slug}/media.bin", range = "bytes=0-1,4-5")

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertEquals("abcdef", response.substringAfter("\r\n\r\n"))
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
    fun servesSiteFromManagedOfflineHostAliasAndStopsRoutingWhenAliasIsDeleted() {
        val site = store.createSite("Managed local domain")
        site.root.resolve("index.html").writeText("managed-local-host")
        val alias = store.createLocalHostAlias(site.id, "shop")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        server = LocalStaticServer(context, store).also { it.start(allowLan = false) }

        val response = request("/", host = "${alias.hostname}:${LocalStaticServer.PORT}")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.endsWith("managed-local-host"))

        store.deleteLocalHostAlias(site.id, alias.id)
        val missingAlias = request("/", host = alias.hostname)
        assertTrue(missingAlias.startsWith("HTTP/1.1 404 Not Found"))
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
        range: String? = null,
    ): String = Socket(address, LocalStaticServer.PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(
            "$method $path HTTP/1.1\r\nHost: $host\r\n${range?.let { "Range: $it\r\n" }.orEmpty()}Connection: close\r\n\r\n".toByteArray(),
        )
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
