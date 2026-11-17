package com.anbhosting.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        context.getSharedPreferences("local-hosting-preferences", Context.MODE_PRIVATE).edit().clear().commit()
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
        assertEquals(2, files.measureUsage(site).fileCount)
        assertEquals(site.root.resolve("index.html").length() + site.root.resolve("assets/style.css").length(), files.measureUsage(site).storedBytes)
        files.delete(site.id, "assets")
        assertFalse(site.root.resolve("assets").exists())
    }

    @Test
    fun editsPythonHandlerSourceFiles() {
        val source = "def handle_request(request):\n    return {'body': request['path']}\n"

        files.writeText(site.id, "main.py", source)

        assertEquals(source, files.readText(site.id, "main.py"))
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

    @Test
    fun installsOnlyTheSelectedPrebuiltGithubFolder() {
        val archive = zipOf(
            "sample-project-abc123/dist/index.html" to "<h1>Published</h1>",
            "sample-project-abc123/dist/assets/site.css" to "body { color: navy; }",
            "sample-project-abc123/README.md" to "Repository documentation",
        )

        files.installGithubArchive(site.id, ByteArrayInputStream(archive), "dist")

        assertEquals("<h1>Published</h1>", site.root.resolve("index.html").readText())
        assertEquals("body { color: navy; }", site.root.resolve("assets/site.css").readText())
        assertFalse(site.root.resolve("README.md").exists())
    }

    @Test
    fun githubArchiveWithoutStaticEntryKeepsExistingFiles() {
        val originalIndex = site.root.resolve("index.html").readText()
        val archive = zipOf("sample-project-abc123/README.md" to "No static site here")

        assertThrows(IllegalArgumentException::class.java) {
            files.installGithubArchive(site.id, ByteArrayInputStream(archive))
        }

        assertEquals(originalIndex, site.root.resolve("index.html").readText())
    }

    @Test
    fun upgradesExistingLocalCatalogToTrafficAndDeploymentTracking() {
        store.close()
        context.deleteDatabase("anb-local-hosting.db")
        val database = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("anb-local-hosting.db").also { it.parentFile?.mkdirs() },
            null,
        )
        database.execSQL(
            """CREATE TABLE sites (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                slug TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        database.execSQL(
            "INSERT INTO sites (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf("legacy-site", "Legacy", "legacy-site", LocalHostingStore.STATUS_STOPPED, 1L, 1L),
        )
        database.version = 1
        database.close()

        store = LocalHostingStore(context)
        store.recordTraffic("legacy-site", 1536)

        assertEquals(LocalHostingStore.RUNTIME_STATIC, store.findSite("legacy-site")?.runtime)
        assertEquals(1536L, store.trafficLast30Days("legacy-site"))
        assertTrue(store.deploymentHistory("legacy-site").isEmpty())
    }

    @Test
    fun keepsScreenOffHostingEnabledByDefaultAndAllowsBatterySavingOptOut() {
        assertTrue(store.isKeepCpuAwakeEnabled())

        store.setKeepCpuAwakeEnabled(false)

        assertFalse(store.isKeepCpuAwakeEnabled())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            entries.forEach { (path, contents) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive.toByteArray()
    }
}
