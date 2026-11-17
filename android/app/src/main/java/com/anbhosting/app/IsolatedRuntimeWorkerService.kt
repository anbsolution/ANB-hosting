package com.anbhosting.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Isolated process foundation for per-site runtimes. It receives only explicit
 * file descriptors; it cannot directly read ANB's app-private files.
 */
class IsolatedRuntimeWorkerService : Service() {
    private val runtimeLock = Any()
    private var pythonRuntimeStarted = false

    private val binder = object : IIsolatedRuntimeWorker.Stub() {
        override fun getProcessUid(): Int = Process.myUid()

        override fun readGrantedFile(file: ParcelFileDescriptor): String =
            ParcelFileDescriptor.AutoCloseInputStream(file).bufferedReader().use { it.readText() }

        override fun privateStorageProbeIsReadable(): Boolean =
            File(filesDir, PRIVATE_STORAGE_PROBE).canRead()

        override fun startPythonRuntime(runtimeArchiveFile: ParcelFileDescriptor): String = synchronized(runtimeLock) {
            Log.i(TAG, "CPython start requested")
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Isolated Python execution requires Android 10 or newer."
            }
            if (pythonRuntimeStarted) {
                runtimeArchiveFile.close()
                return@synchronized "CPython ${BundledPythonRuntime.VERSION}"
            }
            val retainedArchive = try {
                ParcelFileDescriptor.dup(runtimeArchiveFile.fileDescriptor)
            } finally {
                runtimeArchiveFile.close()
            }
            try {
                Log.i(TAG, "initializing CPython from the granted standard-library archive")
                val version = BundledPythonRuntime.initializeIsolatedRuntime(retainedArchive.fd)
                pythonRuntimeStarted = true
                Log.i(TAG, "CPython initialized: $version")
                "CPython $version"
            } catch (error: Exception) {
                Log.e(TAG, "CPython initialization failed", error)
                throw error
            } finally {
                retainedArchive.close()
            }
        }

        override fun executePythonHandler(source: String, requestJson: String): String = synchronized(runtimeLock) {
            check(pythonRuntimeStarted) { "Start the isolated Python runtime first." }
            require(source.toByteArray(StandardCharsets.UTF_8).size <= MAX_PYTHON_SOURCE_BYTES) {
                "Python handler exceeds the 256 KiB source limit."
            }
            require(requestJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_JSON_BYTES) {
                "Python request exceeds the 64 KiB input limit."
            }
            val response = BundledPythonRuntime.runIsolatedHandler(source, requestJson)
            require(response.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_JSON_BYTES) {
                "Python handler response exceeds the 256 KiB output limit."
            }
            response
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(runtimeLock) {
            pythonRuntimeStarted = false
        }
        super.onDestroy()
    }

    companion object {
        const val PRIVATE_STORAGE_PROBE = "isolated-worker-access-probe"
        private const val TAG = "AnbIsolatedRuntime"
        private const val MAX_PYTHON_SOURCE_BYTES = 256 * 1024
        private const val MAX_REQUEST_JSON_BYTES = 64 * 1024
        private const val MAX_RESPONSE_JSON_BYTES = 256 * 1024
    }
}
