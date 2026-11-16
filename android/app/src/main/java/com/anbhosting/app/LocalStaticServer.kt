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
        client.use { connection ->
            try {
                val input = connection.getInputStream()
                val requestLine = readHttpLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: return sendError(connection, 400, "Bad Request")
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
                    return sendError(connection, 400, "Bad Request")
                }
                var headerBytes = 0
                while (true) {
                    val line = readHttpLine(input, MAX_HEADER_LINE_BYTES)
                        ?: return sendError(connection, 400, "Bad Request")
                    headerBytes += line.length
                    if (headerBytes > MAX_HEADER_BYTES) return sendError(connection, 431, "Headers Too Large")
                    if (line.isEmpty()) break
                }

                val method = parts[0].uppercase()
                if (method != "GET" && method != "HEAD") return sendError(connection, 405, "Method Not Allowed")
                val path = resolveRequestPath(parts[1]) ?: return sendError(connection, 400, "Bad Request")
                when (path) {
                    ForbiddenPath -> sendError(connection, 403, "Forbidden")
                    MissingPath -> sendError(connection, 404, "Not Found")
                    is ResolvedPath -> sendFile(connection, path.file, method == "HEAD")
                }
            } catch (error: IOException) {
                store.recordEvent(null, "server.request_failed", error.message.orEmpty())
            } catch (error: Exception) {
                store.recordEvent(null, "server.request_failed", error.message.orEmpty())
                runCatching { sendError(connection, 500, "Internal Server Error") }
            }
        }
    }

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

    private fun sendFile(socket: Socket, file: File, headOnly: Boolean) {
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: when (file.extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "webmanifest" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        writeHeaders(output, 200, "OK", mime, file.length())
        if (!headOnly) FileInputStream(file).use { it.copyTo(output) }
        output.flush()
    }

    private fun sendError(socket: Socket, status: Int, message: String) {
        val body = "<h1>$status $message</h1>".toByteArray(Charsets.UTF_8)
        val output = BufferedOutputStream(socket.getOutputStream())
        writeHeaders(output, status, message, "text/html; charset=utf-8", body.size.toLong())
        output.write(body)
        output.flush()
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
    }
}
