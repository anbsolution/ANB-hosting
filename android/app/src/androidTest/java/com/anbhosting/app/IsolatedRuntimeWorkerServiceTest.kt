package com.anbhosting.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
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
    fun workerHasSeparateUidAndReadsOnlyExplicitlyGrantedFileDescriptor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectionExecutor = Executors.newSingleThreadExecutor()
        val connected = CountDownLatch(1)
        val remoteBinder = AtomicReference<IIsolatedRuntimeWorker?>()
        val siteRoot = File(context.cacheDir, "isolated-runtime-probe-${UUID.randomUUID()}")
        assertTrue(siteRoot.mkdir())
        val visibleSiteFile = File(siteRoot, "site-file.txt")
        visibleSiteFile.writeText("granted through file descriptor")
        val hiddenPrivateFile = File(context.filesDir, IsolatedRuntimeWorkerService.PRIVATE_STORAGE_PROBE)
        hiddenPrivateFile.writeText("must stay private to the app uid")
        var visibleSiteFileDescriptor: ParcelFileDescriptor? = null
        var runtimePrefixDescriptor: ParcelFileDescriptor? = null
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
            visibleSiteFileDescriptor =
                ParcelFileDescriptor.open(visibleSiteFile, ParcelFileDescriptor.MODE_READ_ONLY)
            bound = context.bindIsolatedService(
                Intent(context, IsolatedRuntimeWorkerService::class.java),
                Context.BIND_AUTO_CREATE,
                "site${UUID.randomUUID().toString().replace("-", "")}",
                connectionExecutor,
                connection,
            )
            assertTrue("Android did not bind the isolated runtime worker", bound)
            assertTrue("Isolated runtime worker did not connect", connected.await(10, TimeUnit.SECONDS))

            val worker = requireNotNull(remoteBinder.get())
            assertNotEquals("Worker must run under an isolated UID", Process.myUid(), worker.getProcessUid())
            assertEquals(
                "granted through file descriptor",
                worker.readGrantedFile(requireNotNull(visibleSiteFileDescriptor)),
            )
            assertFalse(
                "Worker must not read ANB app-private files by path",
                worker.privateStorageProbeIsReadable(),
            )

            runtimePrefixDescriptor = BundledPythonRuntime.openRuntimePrefix(context)
            Log.i("IsolatedRuntimeTest", "starting CPython with explicitly granted prefix descriptor")
            val pythonVersion = worker.startPythonRuntime(requireNotNull(runtimePrefixDescriptor))
            Log.i("IsolatedRuntimeTest", "isolated CPython started: $pythonVersion")
            assertTrue("Unexpected isolated CPython version: $pythonVersion", pythonVersion.contains("3.14.10"))

            Log.i("IsolatedRuntimeTest", "executing isolated Python handler")
            val response = JSONObject(
                worker.executePythonHandler(
                    "def handle_request(request):\n" +
                        "    return {'status': 200, 'body': 'offline ' + request['path']}\n",
                    "{\"path\":\"/caf\\u00e9\"}",
                ),
            )
            assertEquals(200, response.getInt("status"))
            assertEquals("offline /café", response.getString("body"))

            Log.i("IsolatedRuntimeTest", "checking isolated Python invocation state")
            val secondResponse = JSONObject(
                worker.executePythonHandler(
                    "def handle_request(request):\n" +
                        "    return {'body': request['value']}\n",
                    "{\"value\":\"fresh invocation\"}",
                ),
            )
            assertEquals("fresh invocation", secondResponse.getString("body"))

            Log.i("IsolatedRuntimeTest", "checking isolated Python syntax error response")
            val failedResponse = JSONObject(
                worker.executePythonHandler("def handle_request(:\n    pass\n", "{}"),
            )
            assertEquals("Python handler execution failed", failedResponse.getString("error"))
        } finally {
            if (bound) context.unbindService(connection)
            visibleSiteFileDescriptor?.close()
            runtimePrefixDescriptor?.close()
            hiddenPrivateFile.delete()
            siteRoot.deleteRecursively()
            connectionExecutor.shutdownNow()
        }
    }
}
