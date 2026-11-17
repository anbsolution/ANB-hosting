package com.anbhosting.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileNotFoundException

/**
 * Installs the checksum-pinned interpreter bundled in the APK and checks that
 * its Android native modules load. This is not a website execution API.
 */
object BundledPythonRuntime {
    const val VERSION = "3.14.10"

    private const val ASSET_ROOT = "python-runtime"
    private val lock = Any()

    init {
        System.loadLibrary("anbpython")
    }

    /** Extracts CPython into app-private storage and runs a fixed import check. */
    fun verifyBundledRuntime(context: Context): String = synchronized(lock) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("This ANB build does not include a CPython package for this device ABI")
        val runtimeParent = File(context.noBackupFilesDir, "python-runtime/$VERSION")
        val runtimeHome = File(runtimeParent, "$abi/prefix")
        if (!isUsableRuntime(runtimeHome)) {
            val staging = File(runtimeParent, "$abi.staging")
            val stagingPrefix = File(staging, "prefix")
            staging.deleteRecursively()
            check(stagingPrefix.mkdirs()) { "Could not prepare the app-private CPython directory" }
            try {
                copyAssetTree(context, "$ASSET_ROOT/$abi/prefix", stagingPrefix)
                check(isUsableRuntime(stagingPrefix)) { "The bundled CPython assets are incomplete" }
                val previous = File(runtimeParent, abi)
                if (previous.exists()) {
                    check(previous.deleteRecursively()) { "Could not replace the previous CPython runtime" }
                }
                check(staging.renameTo(previous)) { "Could not install the bundled CPython runtime" }
            } catch (error: Exception) {
                staging.deleteRecursively()
                throw error
            }
        }
        nativeSmokeTest(runtimeHome.absolutePath)
    }

    private fun isUsableRuntime(prefix: File): Boolean =
        runCatching {
            File(prefix, "VERSION").readText().trim() == VERSION &&
                File(prefix, "lib/python3.14/os.py").isFile &&
                File(prefix, "lib/python3.14/lib-dynload").isDirectory
        }.getOrDefault(false)

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath)
            ?: error("Could not list bundled CPython asset $assetPath")
        if (children.isNotEmpty()) {
            check(destination.mkdirs() || destination.isDirectory) {
                "Could not create app-private CPython directory ${destination.path}"
            }
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(destination, child))
            }
            return
        }

        destination.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Could not create app-private CPython directory ${parent.path}"
            }
        }
        try {
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: FileNotFoundException) {
            // Asset packaging drops empty directories; recreate them when present.
            check(destination.mkdirs() || destination.isDirectory) {
                "Could not create app-private CPython directory ${destination.path}"
            }
        }
    }

    private external fun nativeSmokeTest(runtimeHome: String): String
}
