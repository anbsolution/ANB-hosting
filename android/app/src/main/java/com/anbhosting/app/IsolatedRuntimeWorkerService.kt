package com.anbhosting.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/**
 * Isolated process foundation for per-site runtimes. It receives only explicit
 * file descriptors; it cannot directly read ANB's app-private files.
 */
class IsolatedRuntimeWorkerService : Service() {
    private val binder = object : IIsolatedRuntimeWorker.Stub() {
        override fun getProcessUid(): Int = Process.myUid()

        override fun listGrantedDirectory(directory: ParcelFileDescriptor): Array<String> = directory.use {
            val grantedDirectory = File("/proc/self/fd/${it.fd}")
            check(grantedDirectory.isDirectory) { "The granted descriptor is not a directory." }
            grantedDirectory.list()?.sorted()?.toTypedArray()
                ?: error("The granted directory could not be read.")
        }

        override fun privateStorageProbeIsReadable(): Boolean =
            File(filesDir, PRIVATE_STORAGE_PROBE).canRead()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val PRIVATE_STORAGE_PROBE = "isolated-worker-access-probe"
    }
}
