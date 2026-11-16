package com.anbhosting.app

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalResourceMonitorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun samplesAppMemoryAndAvailableAppStorageWithoutSpecialAccess() {
        val metrics = LocalResourceMonitor(context).sample()

        assertTrue(metrics.appMemoryBytes >= 0L)
        assertTrue(metrics.freeStorageBytes > 0L)
        assertTrue(metrics.appCpuPercent == null || metrics.appCpuPercent in 0f..100f)
    }
}
