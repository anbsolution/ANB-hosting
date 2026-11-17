package com.anbhosting.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLConnection
import java.nio.charset.Charset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Local HTTP server for app-owned static files and isolated Python handlers. */
class LocalStaticServer(private val context: Context, private val store: LocalHostingStore) {
    @Volatile private var sockets: List<ServerSocket> = emptyList()
    @Volatile private var pythonRuntimePool: IsolatedPythonRuntimePool? = null
    private var acceptExecutors: List<ExecutorService> = emptyList()
    private var clientExecutor: ExecutorService? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized
    fun start(allowLan: Boolean) {
        if (sockets.isNotEmpty() && sockets.all { it.isBound && !it.isClosed }) return
        stop()

        val wifiAddress = if (allowLan) LocalNetwork.wifiIpv4Address(context) else null
        val bindAddresses: List<InetAddress> = if (wifiAddress != null) {
            listOf(wifiAddress)
        } else {
            listOfNotNull(
                InetAddress.getByName("127.0.0.1"),
                runCatching { InetAddress.getByName("::1") }.getOrNull(),
            )
        }
        val listeners = mutableListOf<ServerSocket>()
        bindAddresses.forEach { bindAddress ->
            var candidate: ServerSocket? = null
            try {
                val created = ServerSocket()
                candidate = created
                created.reuseAddress = true
                created.bind(InetSocketAddress(bindAddress, PORT), 32)
                listeners += created
            } catch (error: IOException) {
                candidate?.let { runCatching { it.close() } }
                if (listeners.isEmpty() || wifiAddress != null) {
                    listeners.forEach { runCatching { it.close() } }
                    throw error
                }
                store.recordEvent(
                    null,
                    "server.loopback_bind_failed",
                    "${bindAddress.hostAddress}: ${error.message.orEmpty()}",
                )
            }
        }
        check(listeners.isNotEmpty()) { "The local website server could not bind to a loopback address." }
        sockets = listeners.toList()
        pythonRuntimePool = IsolatedPythonRuntimePool(context, store)
        clientExecutor = ThreadPoolExecutor(
            MAX_CLIENTS,
            MAX_CLIENTS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
            ThreadPoolExecutor.AbortPolicy(),
        )
        acceptExecutors = listeners.map { listener ->
            Executors.newSingleThreadExecutor().also { executor ->
                executor.execute { acceptConnections(listener) }
            }
        }
        val detail = wifiAddress?.let { "Wi-Fi sharing at ${it.hostAddress}" } ?: "localhost only"
        store.recordEvent(null, "server.started", detail)
    }

    @Synchronized
    fun stop() {
        sockets.forEach { listener -> runCatching { listener.close() } }
        sockets = emptyList()
        clients.toList().forEach { client -> runCatching { client.close() } }
        clients.clear()
        pythonRuntimePool?.close()
        pythonRuntimePool = null
        acceptExecutors.forEach { it.shutdownNow() }
        acceptExecutors = emptyList()
        clientExecutor?.shutdownNow()
        clientExecutor = null
    }

    private fun acceptConnections(listener: ServerSocket) {
        while (!listener.isClosed) {
            try {
                val client = listener.accept()
                client.soTimeout = SOCKET_TIMEOUT_MS
                clients.add(client)
                val executor = clientExecutor
                if (executor == null) {
                    clients.remove(client)
                    client.close()
                } else {
                    try {
                        executor.execute {
                            try {
                                handleClient(client)
                            } finally {
                                clients.remove(client)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        clients.remove(client)
                        client.close()
                    }
                }
            } catch (_: SocketException) {
                break
            } catch (error: IOException) {
                store.recordEvent(null, "server.accept_failed", error.message.orEmpty())
            }
        }
    }

    private fun handleClient(client: Socket) {
        var requestMethod = ""
        var requestPath = ""
        var requestSiteId: String? = null
        var localHostname: String? = null
        var responseStatus = 0
        var responseBytes = 0L
        var failureMessage: String? = null
        try {
            client.use { connection ->
                try {
                    val input = connection.getInputStream()
                    val requestLine = readHttpLine(input, MAX_REQUEST_LINE_BYTES)
                        ?: run {
                            responseStatus = 400
                            responseBytes = sendError(connection, 400, "Bad Request", requestMethod == "HEAD")
                            return
                        }
                    val parts = requestLine.split(' ', limit = 3)
                    if (parts.size >= 2) {
                        requestMethod = parts[0].uppercase().take(MAX_LOG_METHOD_CHARS)
                        requestPath = sanitizeLogPath(parts[1])
                        requestSiteId = lookupSiteId(parts[1])
                    }
                    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
                        responseStatus = 400
                        responseBytes = sendError(connection, 400, "Bad Request", requestMethod == "HEAD")
                        return
                    }
                    var headerBytes = 0
                    var hostHeader: String? = null
                    var rangeHeader: String? = null
                    var rangeHeaderSeen = false
                    var duplicateRangeHeader = false
                    while (true) {
                        val line = readHttpLine(input, MAX_HEADER_LINE_BYTES)
                            ?: run {
                                responseStatus = 400
                                responseBytes = sendError(connection, 400, "Bad Request", requestMethod == "HEAD")
                                return
                            }
                        headerBytes += line.length
                        if (headerBytes > MAX_HEADER_BYTES) {
                            responseStatus = 431
                            responseBytes = sendError(connection, 431, "Headers Too Large", requestMethod == "HEAD")
                            return
                        }
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0 && line.substring(0, separator).equals("Host", ignoreCase = true)) {
                            if (hostHeader != null) {
                                responseStatus = 400
                                responseBytes = sendError(connection, 400, "Bad Request", requestMethod == "HEAD")
                                return
                            }
                            hostHeader = line.substring(separator + 1).trim()
                        }
                        if (separator > 0 && line.substring(0, separator).equals("Range", ignoreCase = true)) {
                            if (rangeHeaderSeen) {
                                duplicateRangeHeader = true
                            } else {
                                rangeHeaderSeen = true
                                rangeHeader = line.substring(separator + 1).trim()
                            }
                        }
                    }
                    val requestHostname = localHostnameFromHost(hostHeader)
                    localHostname = requestHostname
                    requestSiteId = if (requestHostname != null) {
                        store.findSiteByLocalHostname(requestHostname)?.id
                    } else {
                        lookupSiteId(parts[1])
                    }

                    val method = parts[0].uppercase()
                    if (method != "GET" && method != "HEAD") {
                        responseStatus = 405
                        responseBytes = sendError(connection, 405, "Method Not Allowed", requestMethod == "HEAD")
                        return
                    }
                    val pythonSite = requestSiteId?.let(store::findSite)
                        ?.takeIf { it.runtime == LocalHostingStore.RUNTIME_PYTHON }
                    if (pythonSite != null) {
                        when (val route = resolvePythonRequestPath(pythonSite, parts[1], localHostname)) {
                            null -> {
                                responseStatus = 400
                                responseBytes = sendError(connection, 400, "Bad Request", method == "HEAD")
                            }
                            PythonPathForbidden -> {
                                responseStatus = 403
                                responseBytes = sendError(connection, 403, "Forbidden", method == "HEAD")
                            }
                            PythonPathMissing -> {
                                responseStatus = 404
                                responseBytes = sendError(connection, 404, "Not Found", method == "HEAD")
                            }
                            is PythonRequestPath -> try {
                                val response = pythonRuntimePool?.execute(
                                    pythonSite,
                                    method,
                                    route.path,
                                    route.query,
                                    localHostname,
                                ) ?: throw IOException("The local Python runtime is unavailable.")
                                responseStatus = response.status
                                responseBytes = sendPythonResponse(connection, response, method == "HEAD")
                            } catch (error: Exception) {
                                responseStatus = 500
                                failureMessage = error.message.orEmpty()
                                responseBytes = runCatching {
                                    sendError(connection, 500, "Internal Server Error", method == "HEAD")
                                }.getOrDefault(0L)
                            }
                        }
                    } else {
                        val path = resolveRequestPath(parts[1], localHostname) ?: run {
                            responseStatus = 400
                            responseBytes = sendError(connection, 400, "Bad Request", method == "HEAD")
                            return
                        }
                        when (path) {
                            ForbiddenPath -> {
                                responseStatus = 403
                                responseBytes = sendError(connection, 403, "Forbidden", method == "HEAD")
                            }
                            MissingPath -> {
                                responseStatus = 404
                                responseBytes = sendError(connection, 404, "Not Found", method == "HEAD")
                            }
                            is ResolvedPath -> {
                                val selectedRange = if (duplicateRangeHeader) {
                                    FullFile
                                } else {
                                    selectRange(rangeHeader, path.file.length())
                                }
                                when (selectedRange) {
                                    UnsatisfiableRange -> {
                                        responseStatus = 416
                                        responseBytes = sendError(
                                            connection,
                                            416,
                                            "Range Not Satisfiable",
                                            method == "HEAD",
                                            listOf("Accept-Ranges: bytes", "Content-Range: bytes */${path.file.length()}"),
                                        )
                                    }
                                    FullFile -> {
                                        responseStatus = 200
                                        responseBytes = sendFile(connection, path.file, method == "HEAD")
                                    }
                                    is SelectedRange -> {
                                        responseStatus = 206
                                        responseBytes = sendFile(connection, path.file, method == "HEAD", selectedRange)
                                    }
                                }
                            }
                        }
                    }
                } catch (error: IOException) {
                    failureMessage = error.message.orEmpty()
                } catch (error: Exception) {
                    responseStatus = 500
                    failureMessage = error.message.orEmpty()
                    responseBytes = runCatching {
                        sendError(connection, 500, "Internal Server Error", requestMethod == "HEAD")
                    }.getOrDefault(0L)
                } finally {
                    if (requestPath.isNotBlank()) {
                        val action = when {
                            failureMessage != null -> "request.failed"
                            responseStatus >= 400 -> "request.error"
                            else -> "request.served"
                        }
                        val status = if (responseStatus == 0) "connection-failed" else responseStatus.toString()
                        val detail = "${requestMethod.ifBlank { "UNKNOWN" }} $requestPath → $status" +
                            failureMessage?.takeIf(String::isNotBlank)?.let { " (${it.take(160)})" }.orEmpty()
                        runCatching { store.recordEvent(requestSiteId, action, detail) }
                        requestSiteId?.let { siteId ->
                            runCatching {
                                store.recordHttpRequest(
                                    siteId = siteId,
                                    method = requestMethod.ifBlank { "UNKNOWN" },
                                    path = requestPath,
                                    statusCode = responseStatus,
                                    responseBytes = responseBytes,
                                    errorMessage = failureMessage,
                                )
                            }
                        }
                        if (requestSiteId != null && responseStatus in 200..206 && responseBytes > 0) {
                            runCatching { store.recordTraffic(requestSiteId!!, responseBytes) }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Closing a client socket can fail after its request was already logged.
        }
    }

    private fun lookupSiteId(target: String): String? {
        val parts = target.substringBefore('?').substringBefore('#').split('/')
        if (parts.size < 3 || parts[1] != "s") return null
        val slug = decodePathPart(parts[2]) ?: return null
        return store.findSiteBySlug(slug)?.id
    }

    private fun localHostnameFromHost(hostHeader: String?): String? {
        val authority = hostHeader?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        if (authority.any { it.isWhitespace() } || authority.contains('@')) return null

        val colon = authority.lastIndexOf(':')
        val hostname = if (colon >= 0) {
            if (authority.indexOf(':') != colon) return null
            val port = authority.substring(colon + 1)
            if (port.isEmpty() || !port.all { it.isDigit() }) return null
            val portNumber = port.toIntOrNull() ?: return null
            if (portNumber !in 1..65535) return null
            authority.substring(0, colon)
        } else {
            authority
        }.trimEnd('.')

        val suffix = ".localhost"
        if (!hostname.endsWith(suffix)) return null
        val label = hostname.removeSuffix(suffix)
        return hostname.takeIf { LOCAL_HOST_LABEL.matches(label) }
    }

    private fun sanitizeLogPath(target: String): String = target.substringBefore('?')
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .take(MAX_LOG_PATH_CHARS)

    private fun resolveRequestPath(target: String, localHostname: String?): PathResult? {
        if (!target.startsWith('/') || target.startsWith("//")) return null
        val rawPath = target.substringBefore('?').substringBefore('#')
        if (localHostname != null) {
            val site = store.findSiteByLocalHostname(localHostname) ?: return MissingPath
            return resolveSitePath(site.slug, rawPath.split('/').drop(1))
        }

        val rawParts = rawPath.split('/')
        if (rawParts.size < 3 || rawParts[1] != "s") return MissingPath
        val slug = decodePathPart(rawParts[2]) ?: return null
        if (!slug.matches(Regex("[a-z0-9-]{1,60}"))) return MissingPath

        return resolveSitePath(slug, rawParts.drop(3))
    }

    private fun resolveSitePath(slug: String, rawRelativeParts: List<String>): PathResult? {
        val site = store.findSiteBySlug(slug) ?: return MissingPath
        if (site.status != LocalHostingStore.STATUS_ACTIVE) return MissingPath

        val relativeParts = rawRelativeParts.map { decodePathPart(it) ?: return null }
        if (relativeParts.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
            return ForbiddenPath
        }
        if (relativeParts.any { it.startsWith('.') && it != ".well-known" }) return ForbiddenPath
        val relative = relativeParts.filter(String::isNotEmpty).joinToString(File.separator)
        val root = site.root.canonicalFile
        var file = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) return ForbiddenPath
        if (file.isDirectory) file = File(file, "index.html").canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) return ForbiddenPath
        return if (file.exists()) ResolvedPath(file) else MissingPath
    }

    private fun resolvePythonRequestPath(site: LocalSite, target: String, localHostname: String?): PythonPathResult? {
        if (site.status != LocalHostingStore.STATUS_ACTIVE) return PythonPathMissing
        if (!target.startsWith('/') || target.startsWith("//")) return null
        val rawPath = target.substringBefore('?').substringBefore('#')
        val rawParts = rawPath.split('/')
        val relativeParts = if (localHostname != null) {
            rawParts.drop(1)
        } else {
            if (rawParts.size < 3 || rawParts[1] != "s" || decodePathPart(rawParts[2]) != site.slug) {
                return PythonPathMissing
            }
            rawParts.drop(3)
        }
        val decodedParts = relativeParts.map { decodePathPart(it) ?: return null }
        if (decodedParts.any {
                it == "." || it == ".." || it.contains('/') || it.contains('\\') || it.contains('\u0000')
            }
        ) {
            return PythonPathForbidden
        }
        if (decodedParts.any { it.startsWith('.') && it != ".well-known" }) return PythonPathForbidden
        val path = "/" + decodedParts.filter(String::isNotEmpty).joinToString("/")
        val normalizedPath = if (rawPath.endsWith('/') && path != "/") "$path/" else path
        val query = target.substringBefore('#').substringAfter('?', "")
        return PythonRequestPath(normalizedPath, query)
    }

    private fun decodePathPart(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun sendFile(socket: Socket, file: File, headOnly: Boolean, range: SelectedRange? = null): Long {
        val mime = when (file.extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "webmanifest" -> "application/json; charset=utf-8"
            "txt", "md" -> "text/plain; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        val fileLength = file.length()
        val start = range?.start ?: 0L
        val end = range?.end ?: (fileLength - 1)
        val contentLength = if (fileLength == 0L) 0L else end - start + 1
        val status = if (range == null) 200 else 206
        val reason = if (range == null) "OK" else "Partial Content"
        val responseHeaders = buildList {
            add("Accept-Ranges: bytes")
            if (range != null) add("Content-Range: bytes $start-$end/$fileLength")
        }
        writeHeaders(output, status, reason, mime, contentLength, responseHeaders)
        val bytesSent = if (!headOnly && contentLength > 0) {
            FileInputStream(file).use { input ->
                if (start > 0) input.channel.position(start)
                copyAtMost(input, output, contentLength)
            }
        } else {
            0L
        }
        output.flush()
        return bytesSent
    }

    private fun sendError(
        socket: Socket,
        status: Int,
        message: String,
        headOnly: Boolean = false,
        responseHeaders: List<String> = emptyList(),
    ): Long {
        val body = "<h1>$status $message</h1>".toByteArray(Charsets.UTF_8)
        val output = BufferedOutputStream(socket.getOutputStream())
        writeHeaders(output, status, message, "text/html; charset=utf-8", body.size.toLong(), responseHeaders)
        if (!headOnly) output.write(body)
        output.flush()
        return if (headOnly) 0L else body.size.toLong()
    }

    private fun sendPythonResponse(socket: Socket, response: PythonHttpResponse, headOnly: Boolean): Long {
        val reason = when (response.status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            422 -> "Unprocessable Content"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Python Response"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        writeHeaders(output, response.status, reason, response.contentType, response.body.size.toLong())
        if (!headOnly && response.body.isNotEmpty()) output.write(response.body)
        output.flush()
        return if (headOnly) 0L else response.body.size.toLong()
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long,
        additionalHeaders: List<String> = emptyList(),
    ) {
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $contentLength\r\n" +
            additionalHeaders.joinToString(
                separator = "\r\n",
                postfix = if (additionalHeaders.isEmpty()) "" else "\r\n",
            ) +
            "Connection: close\r\n" +
            "Cache-Control: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n\r\n"
        output.write(headers.toByteArray(Charset.forName("ISO-8859-1")))
    }

    private fun copyAtMost(input: java.io.InputStream, output: BufferedOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (copied < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - copied).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    private fun selectRange(value: String?, fileLength: Long): ByteRangeResult {
        val requested = value?.trim()?.let(BYTE_RANGE_PATTERN::matchEntire) ?: return FullFile
        val first = requested.groupValues[1]
        val last = requested.groupValues[2]
        if (first.isEmpty() && last.isEmpty()) return FullFile

        if (first.isEmpty()) {
            val suffixLength = last.toLongOrNull() ?: return FullFile
            if (suffixLength <= 0 || fileLength <= 0) return UnsatisfiableRange
            return SelectedRange((fileLength - suffixLength).coerceAtLeast(0), fileLength - 1)
        }

        val start = first.toLongOrNull() ?: return FullFile
        if (start >= fileLength) return UnsatisfiableRange
        val requestedEnd = if (last.isEmpty()) {
            fileLength - 1
        } else {
            last.toLongOrNull() ?: return FullFile
        }
        if (requestedEnd < start) return FullFile
        return SelectedRange(start, minOf(requestedEnd, fileLength - 1))
    }

    private fun readHttpLine(input: java.io.InputStream, maxBytes: Int): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= maxBytes) {
            val next = input.read()
            if (next == -1) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (next == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
            line.write(next)
        }
        throw IOException("HTTP line exceeds its size limit")
    }

    private sealed interface PathResult
    private data class ResolvedPath(val file: File) : PathResult
    private data object ForbiddenPath : PathResult
    private data object MissingPath : PathResult

    private sealed interface PythonPathResult
    private data class PythonRequestPath(val path: String, val query: String) : PythonPathResult
    private data object PythonPathForbidden : PythonPathResult
    private data object PythonPathMissing : PythonPathResult

    private sealed interface ByteRangeResult
    private data object FullFile : ByteRangeResult
    private data object UnsatisfiableRange : ByteRangeResult
    private data class SelectedRange(val start: Long, val end: Long) : ByteRangeResult

    companion object {
        const val PORT = 8787
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUED_CLIENTS = 16
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_REQUEST_LINE_BYTES = 8_192
        private const val MAX_HEADER_LINE_BYTES = 8_192
        private const val MAX_HEADER_BYTES = 16_384
        private const val MAX_LOG_PATH_CHARS = 200
        private const val MAX_LOG_METHOD_CHARS = 10
        private val LOCAL_HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?")
        private val BYTE_RANGE_PATTERN = Regex("^bytes=(\\d*)-(\\d*)$", RegexOption.IGNORE_CASE)
    }
}
