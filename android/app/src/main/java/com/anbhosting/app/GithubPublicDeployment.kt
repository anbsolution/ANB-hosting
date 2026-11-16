package com.anbhosting.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class PublicGithubRepository(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"

    companion object {
        fun parse(value: String): PublicGithubRepository {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("Enter a valid GitHub repository URL.")
            require(uri.scheme?.equals("https", ignoreCase = true) == true && uri.host?.equals("github.com", ignoreCase = true) == true) {
                "Use an HTTPS URL from github.com."
            }
            require(uri.userInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null) {
                "Repository URLs must not include credentials, ports, queries, or fragments."
            }
            val rawPath = uri.rawPath.orEmpty()
            require(!rawPath.contains('%')) { "Encoded repository paths are not supported." }
            val parts = rawPath.trim('/').split('/')
            require(parts.size == 2) { "Enter the URL of one repository, not a file or branch page." }
            val owner = parts[0]
            val name = if (parts[1].endsWith(".git", ignoreCase = true)) parts[1].dropLast(4) else parts[1]
            require(owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))) {
                "The GitHub account name is invalid."
            }
            require(
                name.matches(Regex("[A-Za-z0-9._-]{1,100}")) && name != "." && name != "..",
            ) {
                "The GitHub repository name is invalid."
            }
            return PublicGithubRepository(owner, name)
        }
    }
}

internal data class PublicGithubRepositoryMetadata(
    val isPrivate: Boolean,
    val defaultBranch: String,
)

internal interface PublicGithubClient {
    fun repository(repository: PublicGithubRepository): PublicGithubRepositoryMetadata
    fun downloadDefaultBranch(repository: PublicGithubRepository, destination: File)
}

/** Read-only GitHub REST client. It deploys public archives without storing GitHub credentials. */
internal class GithubRestClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build(),
) : PublicGithubClient {
    override fun repository(repository: PublicGithubRepository): PublicGithubRepositoryMetadata {
        val request = githubRequest("https://api.github.com/repos/${repository.owner}/${repository.name}")
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("Public repository not found; private repositories are not supported yet.")
            if (!response.isSuccessful) throw IOException(githubStatusMessage(response.code))
            val body = response.body?.string() ?: throw IOException("GitHub returned an empty repository response.")
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("GitHub returned invalid repository metadata.")
            }
            return PublicGithubRepositoryMetadata(
                isPrivate = json.optBoolean("private", true),
                defaultBranch = json.optString("default_branch"),
            )
        }
    }

    override fun downloadDefaultBranch(repository: PublicGithubRepository, destination: File) {
        val request = githubRequest("https://api.github.com/repos/${repository.owner}/${repository.name}/zipball")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(githubStatusMessage(response.code))
            val host = response.request.url.host.lowercase()
            if (response.request.url.scheme != "https" || host !in ARCHIVE_HOSTS) {
                throw IOException("GitHub redirected the archive to an unsupported host.")
            }
            val body = response.body ?: throw IOException("GitHub returned an empty repository archive.")
            if (body.contentLength() > MAX_COMPRESSED_ARCHIVE_BYTES) {
                throw IOException("The GitHub archive exceeds the 100 MiB download limit.")
            }
            var total = 0L
            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > MAX_COMPRESSED_ARCHIVE_BYTES - total) {
                            throw IOException("The GitHub archive exceeds the 100 MiB download limit.")
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
            if (total == 0L) throw IOException("The GitHub repository archive is empty.")
        }
    }

    private fun githubRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .header("User-Agent", "ANBHosting-Android")
        .get()
        .build()

    private fun githubStatusMessage(code: Int): String = when (code) {
        403, 429 -> "GitHub rate-limited or denied this public repository request. Try again later."
        404 -> "Public repository archive not found. The default branch may be empty."
        else -> "GitHub request failed (HTTP $code)."
    }

    companion object {
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val MAX_COMPRESSED_ARCHIVE_BYTES = 100L * 1024 * 1024
        private val ARCHIVE_HOSTS = setOf("codeload.github.com", "github.com")
    }
}

/** Imports prebuilt static files from a public repository into the local hosting catalog. */
internal class LocalGithubDeployer(
    private val context: Context,
    private val store: LocalHostingStore,
    private val files: LocalFileManager,
    private val github: PublicGithubClient = GithubRestClient(),
) {
    fun deploy(repositoryUrl: String, websiteName: String, publishDirectory: String = ""): LocalSite {
        val repository = PublicGithubRepository.parse(repositoryUrl)
        val name = websiteName.trim()
        require(name.length in 2..80) { "Website name must be 2 to 80 characters." }
        store.recordEvent(null, "deployment.started", "GitHub ${repository.fullName}")

        cleanupStaleArchives()
        val archive = File(context.cacheDir, "anb-github-${UUID.randomUUID()}.zip")
        var site: LocalSite? = null
        var archiveInstalled = false
        try {
            val metadata = github.repository(repository)
            require(!metadata.isPrivate) { "Private GitHub repositories are not supported yet." }
            require(metadata.defaultBranch.isNotBlank()) { "GitHub did not report a default branch." }

            github.downloadDefaultBranch(repository, archive)
            store.recordEvent(null, "deployment.archive_downloaded", "GitHub ${repository.fullName} · ${archive.length()} bytes")

            site = store.createSite(name)
            files.installGithubArchive(site.id, archive.inputStream(), publishDirectory)
            archiveInstalled = true
            store.recordEvent(
                site.id,
                "deployment.succeeded",
                "GitHub ${repository.fullName} · ${metadata.defaultBranch} · ${publishDirectory.ifBlank { "/" }}",
            )
            return site
        } catch (failure: Exception) {
            if (!archiveInstalled && site != null) runCatching { store.deleteSite(site.id) }
            runCatching {
                store.recordEvent(
                    site?.id,
                    "deployment.failed",
                    "GitHub ${repository.fullName} · ${(failure.message ?: "Deployment failed").take(300)}",
                )
            }
            throw failure
        } finally {
            archive.delete()
        }
    }

    private fun cleanupStaleArchives() {
        val cutoff = System.currentTimeMillis() - MAX_CACHED_ARCHIVE_AGE_MS
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("anb-github-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_CACHED_ARCHIVE_AGE_MS = 24L * 60 * 60 * 1000
    }
}
