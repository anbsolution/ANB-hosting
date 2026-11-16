package com.anbhosting.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFileManagerTest {
    private lateinit var context: Context
    private lateinit var store: LocalHostingStore
    private lateinit var files: LocalFileManager
    private lateinit var site: LocalSite

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("anb-local-hosting.db")
        context.filesDir.resolve("local-sites").deleteRecursively()
        store = LocalHostingStore(context)
        site = store.createSite("File tools")
        files = LocalFileManager(store)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun editsImportsRenamesAndDeletesWebsiteFiles() {
        files.writeText(site.id, "index.html", "<h1>Edited offline</h1>")
        files.createDirectory(site.id, "assets")
        files.importFile(site.id, "assets/site.css", ByteArrayInputStream("body { color: blue; }".toByteArray()))

        files.rename(site.id, "assets/site.css", "style.css")
        val output = ByteArrayOutputStream()
        files.exportFile(site.id, "assets/style.css", output)

        assertEquals("<h1>Edited offline</h1>", files.readText(site.id, "index.html"))
        assertEquals("body { color: blue; }", output.toString("UTF-8"))
        files.delete(site.id, "assets")
        assertFalse(site.root.resolve("assets").exists())
    }

    @Test
    fun backupRestoreReplacesSiteContentsAndRejectsInvalidBackup() {
        files.writeText(site.id, "index.html", "<h1>Before backup</h1>")
        files.writeText(site.id, "extra.txt", "Keep in backup")
        val backup = ByteArrayOutputStream().also { files.createBackup(site.id, it) }.toByteArray()

        files.writeText(site.id, "index.html", "<h1>Temporary change</h1>")
        files.writeText(site.id, "remove-after-restore.txt", "temporary")
        files.restoreBackup(site.id, ByteArrayInputStream(backup))

        assertEquals("<h1>Before backup</h1>", files.readText(site.id, "index.html"))
        assertEquals("Keep in backup", files.readText(site.id, "extra.txt"))
        assertFalse(site.root.resolve("remove-after-restore.txt").exists())
        assertThrows(IllegalArgumentException::class.java) {
            files.restoreBackup(site.id, ByteArrayInputStream("not a zip".toByteArray()))
        }
        assertEquals("<h1>Before backup</h1>", files.readText(site.id, "index.html"))
    }

    @Test
    fun zipExtractionRejectsPathTraversal() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("unsafe".toByteArray())
            zip.closeEntry()
        }
        files.importFile(site.id, "unsafe.zip", ByteArrayInputStream(archive.toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            files.extractZip(site.id, "unsafe.zip", "unzipped")
        }
        assertFalse(site.root.resolve("outside.txt").exists())
        assertFalse(site.root.resolve("unzipped").exists())
    }
}
