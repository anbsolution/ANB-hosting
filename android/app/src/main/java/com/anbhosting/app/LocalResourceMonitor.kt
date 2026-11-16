package com.anbhosting.app

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock

internal data class LocalDeviceMetrics(
    val appMemoryBytes: Long,
    val freeStorageBytes: Long,
    val appCpuPercent: Float?,
)

/** Reads device-side measurements without requesting broad storage or usage access. */
internal class LocalResourceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private var previousCpuMillis: Long? = null
    private var previousWallMillis: Long? = null

    @Synchronized
    fun sample(): LocalDeviceMetrics {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        }.getOrNull()
        val appMemoryBytes = (memory?.totalPss?.toLong() ?: 0L) * 1024L
        val freeStorage = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val cpuMillis = Process.getElapsedCpuTime()
        val wallMillis = SystemClock.elapsedRealtime()
        val cpuPercent = previousCpuMillis?.let { lastCpu ->
            val elapsedCpu = (cpuMillis - lastCpu).coerceAtLeast(0L)
            val elapsedWall = (wallMillis - (previousWallMillis ?: wallMillis)).coerceAtLeast(1L)
            val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((elapsedCpu.toFloat() / elapsedWall) * (100f / processorCount)).coerceIn(0f, 100f)
        }
        previousCpuMillis = cpuMillis
        previousWallMillis = wallMillis
        return LocalDeviceMetrics(appMemoryBytes, freeStorage, cpuPercent)
    }
}
