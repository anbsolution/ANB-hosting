package com.anbhosting.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledPythonRuntimeTest {
    @Test
    fun packagedInterpreterLoadsEveryProductionNativeModule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = BundledPythonRuntime.verifyBundledRuntime(context)

        assertTrue("Unexpected CPython version: $version", version.startsWith("3.14.10"))
    }
}
