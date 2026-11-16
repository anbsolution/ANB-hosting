package com.anbhosting.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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

/** Read-only HTTP server for app-owned static website files. */
class LocalStaticServer(private val context: Context, private val store: LocalHostingStore) {
    @Volatile private var socket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var clientExecutor: ExecutorService? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized
    fun start(allowLan: Boolean) {
        if (socket?.isBound == true && socket?.isClosed == false) return
        stop()

        val wifiAddress = if (allowLan) LocalNetwork.wifiIpv4Address(context) else null
        val listener = ServerSocket()
        listener.reuseAddress = true
        val bindAddress = wifiAddress ?: java.net.InetAddress.getByName("127.0.0.1")
        listener.bind(InetSocketAddress(bindAddress, PORT), 32)
        socket = listener
        acceptExecutor = Executors.newSingleThreadExecutor()
        clientExecutor = ThreadPoolExecutor(
            MAX_CLIENTS,
            MAX_CLIENTS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
            ThreadPoolExecutor.AbortPolicy(),
        )
        acceptExecutor?.execute { acceptConnections(listener) }
        val detail = wifiAddress?.let { "Wi-Fi sharing at ${it.hostAddress}" } ?: "localhost only"
        store.recordEvent(null, "server.started", detail)
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        clients.toList().forEach { client -> runCatching { client.close() } }
        clients.clear()
        acceptExecutor?.shutdownNow()
        clientExecutor?.shutdownNow()
        acceptExecutor = null
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
                    }

                    val method = parts[0].uppercase()
                    if (method != "GET" && method != "HEAD") {
                        responseStatus = 405
                        responseBytes = sendError(connection, 405, "Method Not Allowed", requestMethod == "HEAD")
                        return
                    }
                    val path = resolveRequestPath(parts[1]) ?: run {
                        responseStatus = 400
                        responseBytes = sendError(connection, 400, "Bad Request", requestMethod == "HEAD")
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
                            responseStatus = 200
                            responseBytes = sendFile(connection, path.file, method == "HEAD")
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
                        if (requestSiteId != null && responseStatus == 200 && responseBytes > 0) {
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

    private fun sanitizeLogPath(target: String): String = target.substringBefore('?')
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .take(MAX_LOG_PATH_CHARS)

    private fun resolveRequestPath(target: String): PathResult? {
        if (!target.startsWith('/') || target.startsWith("//")) return null
        val rawPath = target.substringBefore('?').substringBefore('#')
        val rawParts = rawPath.split('/')
        if (rawParts.size < 3 || rawParts[1] != "s") return MissingPath
        val slug = decodePathPart(rawParts[2]) ?: return null
        if (!slug.matches(Regex("[a-z0-9-]{1,60}"))) return MissingPath

        val site = store.findSiteBySlug(slug) ?: return MissingPath
        if (site.status != LocalHostingStore.STATUS_ACTIVE) return MissingPath

        val relativeParts = rawParts.drop(3).map { decodePathPart(it) ?: return null }
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

    private fun decodePathPart(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun sendFile(socket: Socket, file: File, headOnly: Boolean): Long {
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
        writeHeaders(output, 200, "OK", mime, file.length())
        val bytesSent = if (!headOnly) FileInputStream(file).use { it.copyTo(output) } else 0L
        output.flush()
        return bytesSent
    }

    private fun sendError(socket: Socket, status: Int, message: String, headOnly: Boolean = false): Long {
        val body = "<h1>$status $message</h1>".toByteArray(Charsets.UTF_8)
        val output = BufferedOutputStream(socket.getOutputStream())
        writeHeaders(output, status, message, "text/html; charset=utf-8", body.size.toLong())
        if (!headOnly) output.write(body)
        output.flush()
        return if (headOnly) 0L else body.size.toLong()
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long,
    ) {
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n\r\n"
        output.write(headers.toByteArray(Charset.forName("ISO-8859-1")))
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
    }
}
