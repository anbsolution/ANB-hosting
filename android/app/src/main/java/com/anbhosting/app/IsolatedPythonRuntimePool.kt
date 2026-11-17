package com.anbhosting.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class PythonHttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

/** Starts one isolated interpreter process per Python site and routes bounded HTTP requests to it. */
internal class IsolatedPythonRuntimePool(
    private val context: Context,
    private val store: LocalHostingStore,
) : AutoCloseable {
    private val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val workers = HashMap<String, WorkerConnection>()

    fun execute(site: LocalSite, method: String, path: String, query: String, hostname: String?): PythonHttpResponse {
        pruneInactiveWorkers()
        check(site.runtime == LocalHostingStore.RUNTIME_PYTHON) { "This website does not use Python." }
        check(site.status == LocalHostingStore.STATUS_ACTIVE) { "This website is stopped." }
        val sourceFile = File(site.root, "main.py")
        check(sourceFile.isFile && !Files.isSymbolicLink(sourceFile.toPath())) {
            "Python website needs a regular main.py file."
        }
        require(sourceFile.length() <= MAX_SOURCE_BYTES) { "main.py exceeds the 256 KiB execution limit." }
        val source = sourceFile.readText(Charsets.UTF_8)
        val requestJson = JSONObject()
            .put("method", method)
            .put("path", path)
            .put("query", query)
            .put("host", hostname.orEmpty())
            .toString()
        require(requestJson.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "Python request exceeds the 64 KiB execution limit."
        }

        val worker = try {
            workerFor(site).connect()
        } catch (error: Exception) {
            discard(site.id)
            throw IOException("The isolated Python runtime could not start.", error)
        }
        val responseJson = try {
            requireNotNull(worker.executePythonHandler(source, requestJson)) {
                "The isolated Python worker returned no response."
            }
        } catch (error: Exception) {
            discard(site.id)
            throw IOException("The isolated Python worker could not execute this request.", error)
        }
        return parseResponse(responseJson)
    }

    private fun workerFor(site: LocalSite): WorkerConnection = synchronized(workers) {
        workers[site.id] ?: run {
            check(workers.size < MAX_ISOLATED_WORKERS) {
                "This phone is already running the maximum number of Python websites. Stop one and try again."
            }
            WorkerConnection(site).also { workers[site.id] = it }
        }
    }

    private fun discard(siteId: String) {
        val connection = synchronized(workers) { workers.remove(siteId) }
        connection?.close()
    }

    private fun parseResponse(json: String): PythonHttpResponse {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Python response exceeds the 256 KiB execution limit."
        }
        val response = JSONObject(json)
        val status = response.optInt("status", 200)
        require(status in 200..599) { "Python handler returned an invalid HTTP status." }
        val contentType = response.optString("content_type", DEFAULT_CONTENT_TYPE)
        require(CONTENT_TYPE_PATTERN.matches(contentType)) {
            "Python handler returned an invalid content type."
        }
        val bodyValue = response.opt("body")
        require(bodyValue is String) { "Python handler must return a string body." }
        val body = bodyValue.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_RESPONSE_BYTES) { "Python response body exceeds the 256 KiB limit." }
        return PythonHttpResponse(status, contentType, body)
    }

    private fun pruneInactiveWorkers() {
        val activePythonSiteIds = store.listSites()
            .asSequence()
            .filter { it.runtime == LocalHostingStore.RUNTIME_PYTHON && it.status == LocalHostingStore.STATUS_ACTIVE }
            .mapTo(HashSet()) { it.id }
        val stoppedConnections = synchronized(workers) {
            workers.keys.filterNot(activePythonSiteIds::contains).mapNotNull(workers::remove)
        }
        stoppedConnections.forEach(WorkerConnection::close)
    }

    override fun close() {
        val connections = synchronized(workers) {
            workers.values.toList().also { workers.clear() }
        }
        connections.forEach(WorkerConnection::close)
        connectionExecutor.shutdownNow()
    }

    private inner class WorkerConnection(private val site: LocalSite) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val remote = AtomicReference<IIsolatedRuntimeWorker?>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(IIsolatedRuntimeWorker.Stub.asInterface(service))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote.set(null)
            }
        }
        private var bound = false

        @Synchronized
        fun connect(): IIsolatedRuntimeWorker {
            remote.get()?.let { return it }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "On-device Python hosting requires Android 10 or newer."
            }
            if (!bound) {
                val instanceName = "site${site.id.filter(Char::isLetterOrDigit)}"
                bound = context.bindIsolatedService(
                    Intent(context, IsolatedRuntimeWorkerService::class.java),
                    Context.BIND_AUTO_CREATE,
                    instanceName,
                    connectionExecutor,
                    connection,
                )
                check(bound) { "Android could not start this website's isolated Python worker." }
            }
            if (!connected.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                close()
                throw IOException("The isolated Python worker did not start in time.")
            }
            val worker = remote.get() ?: run {
                close()
                throw IOException("The isolated Python worker disconnected during startup.")
            }
            val version = try {
                requireNotNull(worker.startPythonRuntime(BundledPythonRuntime.isolatedRuntimeStandardLibraryPath(context))) {
                    "The isolated Python worker returned no startup status."
                }
            } catch (error: Exception) {
                close()
                throw IOException("The on-device Python runtime could not be initialized.", error)
            }
            check(version.contains(BundledPythonRuntime.VERSION)) {
                close()
                "The isolated worker started an unexpected Python version."
            }
            Log.i(TAG, "Started Python $version for ${site.slug}")
            return worker
        }

        @Synchronized
        override fun close() {
            remote.set(null)
            if (bound) {
                bound = false
                runCatching { context.unbindService(connection) }
            }
        }
    }

    companion object {
        private const val TAG = "AnbPythonHosting"
        private const val CONNECTION_TIMEOUT_SECONDS = 15L
        const val MAX_ISOLATED_WORKERS = 2
        private const val MAX_SOURCE_BYTES = 256 * 1024
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val DEFAULT_CONTENT_TYPE = "text/html; charset=utf-8"
        private val CONTENT_TYPE_PATTERN = Regex(
            "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:; charset=[A-Za-z0-9_-]+)?",
        )
    }
}
