package com.anbhosting.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class IsolatedRuntimeWorkerServiceTest {
    @Test
    fun workerHasSeparateUidAndUsesExplicitDirectoryGrant() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectionExecutor = Executors.newSingleThreadExecutor()
        val connected = CountDownLatch(1)
        val remoteBinder = AtomicReference<IIsolatedRuntimeWorker?>()
        val siteRoot = File(context.cacheDir, "isolated-runtime-probe-${UUID.randomUUID()}")
        assertTrue(siteRoot.mkdir())
        val visibleSiteFile = File(siteRoot, "site-file.txt")
        visibleSiteFile.writeText("granted through directory descriptor")
        val hiddenPrivateFile = File(context.filesDir, IsolatedRuntimeWorkerService.PRIVATE_STORAGE_PROBE)
        hiddenPrivateFile.writeText("must stay private to the app uid")
        var siteRootDescriptor: ParcelFileDescriptor? = null
        var bound = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remoteBinder.set(IIsolatedRuntimeWorker.Stub.asInterface(service))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remoteBinder.set(null)
            }
        }

        try {
            siteRootDescriptor = ParcelFileDescriptor.open(siteRoot, ParcelFileDescriptor.MODE_READ_ONLY)
            bound = context.bindIsolatedService(
                Intent(context, IsolatedRuntimeWorkerService::class.java),
                Context.BIND_AUTO_CREATE,
                "site-${UUID.randomUUID()}",
                connectionExecutor,
                connection,
            )
            assertTrue("Android did not bind the isolated runtime worker", bound)
            assertTrue("Isolated runtime worker did not connect", connected.await(10, TimeUnit.SECONDS))

            val worker = requireNotNull(remoteBinder.get())
            assertNotEquals("Worker must run under an isolated UID", Process.myUid(), worker.getProcessUid())
            assertEquals(listOf(visibleSiteFile.name), worker.listGrantedDirectory(siteRootDescriptor))
            assertFalse(
                "Worker must not read ANB app-private files by path",
                worker.privateStorageProbeIsReadable(),
            )
        } finally {
            if (bound) context.unbindService(connection)
            siteRootDescriptor?.close()
            hiddenPrivateFile.delete()
            siteRoot.deleteRecursively()
            connectionExecutor.shutdownNow()
        }
    }
}
