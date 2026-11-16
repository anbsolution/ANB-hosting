package com.anbhosting.app

import android.content.Context
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GithubPublicDeploymentTest {
    private lateinit var context: Context
    private lateinit var store: LocalHostingStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("anb-local-hosting.db")
        context.filesDir.resolve("local-sites").deleteRecursively()
        store = LocalHostingStore(context)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun parsesOnlyPlainPublicGithubRepositoryUrls() {
        assertEquals("anbsolution/ANB-hosting", PublicGithubRepository.parse("https://github.com/anbsolution/ANB-hosting.git").fullName)
        assertThrows(IllegalArgumentException::class.java) {
            PublicGithubRepository.parse("http://github.com/anbsolution/ANB-hosting")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicGithubRepository.parse("https://github.com/anbsolution/ANB-hosting/tree/main/docs")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicGithubRepository.parse("https://user@github.com/anbsolution/ANB-hosting")
        }
    }

    @Test
    fun downloadsAndInstallsASelectedStaticFolderAndRecordsDeploymentEvents() {
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf(
                "demo-123/dist/index.html" to "<h1>GitHub deployed</h1>",
                "demo-123/dist/assets/app.css" to "body { margin: 0; }",
                "demo-123/README.md" to "Do not publish the repository root",
            ),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        val site = deployer.deploy("https://github.com/example/demo", "GitHub demo", "dist")

        assertEquals(LocalHostingStore.STATUS_STOPPED, site.status)
        assertEquals("<h1>GitHub deployed</h1>", site.root.resolve("index.html").readText())
        assertEquals("body { margin: 0; }", site.root.resolve("assets/app.css").readText())
        assertFalse(site.root.resolve("README.md").exists())
        assertTrue(client.repositoryRequested)
        assertTrue(client.archiveRequested)
        assertTrue(store.recentEvents(30).any { it.action == "deployment.started" && it.detail == "GitHub example/demo" })
        assertTrue(store.recentEvents(30).any { it.action == "deployment.succeeded" && it.detail.contains("main") })
    }

    @Test
    fun rejectsPrivateRepositoriesAndLeavesNoWebsiteFiles() {
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = true, defaultBranch = "main"),
            archive = byteArrayOf(),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy("https://github.com/example/private", "Private site")
        }

        assertTrue(store.listSites().isEmpty())
        assertTrue(store.recentEvents(30).any { it.action == "deployment.failed" })
        assertFalse(client.archiveRequested)
    }

    @Test
    fun failedArchiveImportRemovesTheNewSiteAndRecordsFailure() {
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf("demo-123/README.md" to "Not deployable"),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy("https://github.com/example/demo", "Broken site")
        }

        assertTrue(store.listSites().isEmpty())
        assertTrue(store.recentEvents(30).any { it.action == "deployment.failed" })
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

    private class FakeGithubClient(
        private val metadata: PublicGithubRepositoryMetadata,
        private val archive: ByteArray,
    ) : PublicGithubClient {
        var repositoryRequested = false
        var archiveRequested = false

        override fun repository(repository: PublicGithubRepository): PublicGithubRepositoryMetadata {
            repositoryRequested = true
            return metadata
        }

        override fun downloadDefaultBranch(repository: PublicGithubRepository, destination: File) {
            archiveRequested = true
            destination.writeBytes(archive)
        }
    }
}
