package com.anbhosting.app

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Installs the checksum-pinned interpreter bundled in the APK. Website code is
 * executed only by the isolated runtime worker, never in the app process.
 */
object BundledPythonRuntime {
    const val VERSION = "3.14.10"

    private const val ASSET_ROOT = "python-runtime"
    private val lock = Any()

    init {
        System.loadLibrary("anbpython")
    }

    /** Installs CPython into app-private storage and returns its runtime prefix directory. */
    fun installBundledRuntime(context: Context): File = synchronized(lock) {
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
        runtimeHome
    }

    /** Extracts CPython and imports every packaged production extension in this process. */
    fun verifyBundledRuntime(context: Context): String = nativeSmokeTest(
        installBundledRuntime(context).absolutePath,
    )

    /**
     * Opens the pure-Python archive as one read-only file. Android's isolated
     * UID can read the granted descriptor without traversing app-private paths.
     */
    fun openRuntimeArchive(context: Context): ParcelFileDescriptor = synchronized(lock) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("This ANB build does not include a CPython package for this device ABI")
        val runtimeParent = File(context.noBackupFilesDir, "python-runtime/$VERSION")
        val archive = File(runtimeParent, "$abi/python-stdlib.zip")
        if (!archive.isFile || archive.length() == 0L) {
            val staging = File(runtimeParent, "$abi/python-stdlib.staging")
            staging.delete()
            staging.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) { "Could not prepare the CPython archive directory" }
            }
            try {
                context.assets.open("$ASSET_ROOT/$abi/python-stdlib.zip").use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                }
                check(staging.length() > 0L) { "The bundled CPython standard library archive is empty" }
                check(staging.renameTo(archive)) { "Could not install the CPython standard library archive" }
            } catch (error: Exception) {
                staging.delete()
                throw error
            }
        }
        val isolatedArchiveFd = nativeCreateIsolatedRuntimeArchive(archive.absolutePath)
        check(isolatedArchiveFd >= 0) { "Could not prepare the isolated CPython archive" }
        ParcelFileDescriptor.adoptFd(isolatedArchiveFd)
    }

    internal fun initializeIsolatedRuntime(runtimeArchiveFd: Int): String =
        nativeInitializeIsolatedRuntime(runtimeArchiveFd)

    internal fun runIsolatedHandler(source: String, requestJson: String): String =
        nativeRunIsolatedHandler(source, requestJson)

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

    private external fun nativeCreateIsolatedRuntimeArchive(archivePath: String): Int

    private external fun nativeInitializeIsolatedRuntime(runtimeArchiveFd: Int): String

    private external fun nativeRunIsolatedHandler(source: String, requestJson: String): String
}
