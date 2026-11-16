package com.anbhosting.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        context.filesDir.resolve("github-deployment-backups").deleteRecursively()
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
        assertEquals("main", client.resolvedRef)
        assertTrue(client.archiveRequested)
        assertEquals(client.commitSha, client.downloadedCommitSha)
        assertTrue(store.recentEvents(30).any { it.action == "deployment.started" && it.detail == "GitHub example/demo" })
        assertTrue(store.recentEvents(30).any { it.action == "deployment.succeeded" && it.detail.contains("main") && it.detail.contains(client.commitSha) })
    }

    @Test
    fun resolvesSelectedBranchToCommitAndDownloadsPinnedArchive() {
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf("demo-123/dist/index.html" to "<h1>Feature branch</h1>"),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        val site = deployer.deploy("https://github.com/example/demo", "Feature site", "dist", "feature/docs")

        assertEquals("feature/docs", client.resolvedRef)
        assertEquals(client.commitSha, client.downloadedCommitSha)
        assertEquals("<h1>Feature branch</h1>", site.root.resolve("index.html").readText())
        assertTrue(store.recentEvents(30).any {
            it.action == "deployment.succeeded" && it.detail.contains("feature/docs") && it.detail.contains(client.commitSha)
        })
    }

    @Test
    fun updatesStoppedSiteWithBackupAndRollsBackToPreviousFiles() {
        val site = store.createSite("Existing site")
        val originalIndex = site.root.resolve("index.html").readText()
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf(
                "demo-123/dist/index.html" to "<h1>Updated release</h1>",
                "demo-123/dist/new-file.txt" to "Rollback removes this file",
            ),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        val updated = deployer.deploy(
            "https://github.com/example/demo",
            site.name,
            "dist",
            targetSiteId = site.id,
        )
        val deployment = store.latestAppliedDeployment(site.id)

        assertEquals(site.id, updated.id)
        assertEquals("<h1>Updated release</h1>", site.root.resolve("index.html").readText())
        assertTrue(site.root.resolve("new-file.txt").isFile)
        assertTrue(deployment?.backupFileName?.let { File(context.filesDir, "github-deployment-backups/$it").isFile } == true)
        assertTrue(deployment != null)

        deployer.rollback(checkNotNull(deployment).id, site.id)

        assertEquals(originalIndex, site.root.resolve("index.html").readText())
        assertFalse(site.root.resolve("new-file.txt").exists())
        assertEquals(LocalHostingStore.DEPLOYMENT_ROLLED_BACK, store.deploymentHistory(site.id).single().status)
        assertNull(store.latestAppliedDeployment(site.id))
        assertTrue(store.recentEvents(30).any { it.action == "deployment.rolled_back" })
    }

    @Test
    fun keepsOnlyThreeRollbackSnapshotsPerSite() {
        val site = store.createSite("Release site")
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf("demo-123/index.html" to "<h1>Release</h1>"),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        repeat(4) {
            deployer.deploy("https://github.com/example/demo", site.name, targetSiteId = site.id)
        }

        assertEquals(3, store.deploymentHistory(site.id).size)
        val remainingBackups = store.deploymentHistory(site.id).map { it.backupFileName }.toSet()
        val backupDirectory = File(context.filesDir, "github-deployment-backups")
        val storedBackups = backupDirectory.listFiles().orEmpty()
        assertEquals(3, storedBackups.size)
        assertTrue(storedBackups.all { it.name in remainingBackups })

        store.deleteSite(site.id)

        assertTrue(backupDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun refusesToUpdateWhileSiteIsRunning() {
        val site = store.createSite("Running site")
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = byteArrayOf(),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        assertThrows(IllegalStateException::class.java) {
            deployer.deploy("https://github.com/example/demo", site.name, targetSiteId = site.id)
        }

        assertFalse(client.repositoryRequested)
        assertTrue(store.deploymentHistory(site.id).isEmpty())
    }

    @Test
    fun refusesRollbackWhileSiteIsRunning() {
        val site = store.createSite("Running rollback site")
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf("demo-123/index.html" to "<h1>New release</h1>"),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)
        deployer.deploy("https://github.com/example/demo", site.name, targetSiteId = site.id)
        val deployment = checkNotNull(store.latestAppliedDeployment(site.id))
        store.setSiteStatus(site.id, LocalHostingStore.STATUS_ACTIVE)

        assertThrows(IllegalStateException::class.java) {
            deployer.rollback(deployment.id, site.id)
        }

        assertEquals("<h1>New release</h1>", site.root.resolve("index.html").readText())
        assertEquals(LocalHostingStore.DEPLOYMENT_APPLIED, store.latestAppliedDeployment(site.id)?.status)
    }

    @Test
    fun rejectsUnsafeSelectedRefBeforeCallingGithub() {
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = byteArrayOf(),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy("https://github.com/example/demo", "Unsafe ref", selectedRef = "../main")
        }

        assertFalse(client.repositoryRequested)
        assertTrue(store.listSites().isEmpty())
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

    @Test
    fun failedUpdateKeepsExistingFilesAndRemovesTemporaryBackup() {
        val site = store.createSite("Keep existing files")
        val originalIndex = site.root.resolve("index.html").readText()
        val client = FakeGithubClient(
            metadata = PublicGithubRepositoryMetadata(isPrivate = false, defaultBranch = "main"),
            archive = zipOf("demo-123/README.md" to "No static entry"),
        )
        val deployer = LocalGithubDeployer(context, store, LocalFileManager(store), client)

        assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy("https://github.com/example/demo", site.name, targetSiteId = site.id)
        }

        assertEquals(originalIndex, site.root.resolve("index.html").readText())
        assertTrue(store.deploymentHistory(site.id).isEmpty())
        assertTrue(context.filesDir.resolve("github-deployment-backups").listFiles().orEmpty().isEmpty())
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
        val commitSha = "a".repeat(40)
        var repositoryRequested = false
        var resolvedRef: String? = null
        var archiveRequested = false
        var downloadedCommitSha: String? = null

        override fun repository(repository: PublicGithubRepository): PublicGithubRepositoryMetadata {
            repositoryRequested = true
            return metadata
        }

        override fun resolveCommit(repository: PublicGithubRepository, ref: String): String {
            resolvedRef = ref
            return commitSha
        }

        override fun downloadCommit(repository: PublicGithubRepository, commitSha: String, destination: File) {
            archiveRequested = true
            downloadedCommitSha = commitSha
            destination.writeBytes(archive)
        }
    }
}
