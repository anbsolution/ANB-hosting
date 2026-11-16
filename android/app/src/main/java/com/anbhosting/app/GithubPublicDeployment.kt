package com.anbhosting.app

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
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

internal object GithubDeploymentRef {
    fun validateSelected(value: String): String {
        val ref = value.trim()
        if (ref.isEmpty()) return ""
        require(ref.length <= 255) { "Branch, tag, or commit must be 255 characters or fewer." }
        require(ref != "@" && !ref.startsWith('-') && !ref.startsWith('/') && !ref.endsWith('/')) {
            "Enter a valid Git branch, tag, or commit."
        }
        require(!ref.contains("..") && !ref.contains("//") && !ref.contains("@{")) {
            "Enter a valid Git branch, tag, or commit."
        }
        require(ref.none { it.isWhitespace() || Character.isISOControl(it) || it in "~^:?*[\\" }) {
            "Branch or tag contains unsupported characters."
        }
        require(ref.split('/').none { it.startsWith('.') || it.endsWith('.') || it.endsWith(".lock") }) {
            "Enter a valid Git branch, tag, or commit."
        }
        return ref
    }

    fun resolveSelectedOrDefault(selected: String, defaultBranch: String): String {
        val ref = if (selected.isBlank()) defaultBranch else selected
        require(ref.isNotBlank()) { "GitHub did not report a default branch." }
        return validateSelected(ref)
    }
}

internal interface PublicGithubClient {
    fun repository(repository: PublicGithubRepository): PublicGithubRepositoryMetadata
    fun resolveCommit(repository: PublicGithubRepository, ref: String): String
    fun downloadCommit(repository: PublicGithubRepository, commitSha: String, destination: File)
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
        val request = githubRequest(githubApiUrl("repos", repository.owner, repository.name))
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

    override fun resolveCommit(repository: PublicGithubRepository, ref: String): String {
        val request = githubRequest(githubApiUrl("repos", repository.owner, repository.name, "commits", ref))
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) throw IOException("The requested public branch, tag, or commit was not found.")
                throw IOException(githubStatusMessage(response.code))
            }
            val body = response.body?.string() ?: throw IOException("GitHub returned an empty commit response.")
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("GitHub returned invalid commit metadata.")
            }
            val sha = json.optString("sha")
            if (!sha.matches(Regex("[a-fA-F0-9]{40}"))) {
                throw IOException("GitHub did not return a valid commit SHA.")
            }
            return sha.lowercase()
        }
    }

    override fun downloadCommit(repository: PublicGithubRepository, commitSha: String, destination: File) {
        require(commitSha.matches(Regex("[a-fA-F0-9]{40}"))) { "GitHub returned an invalid commit SHA." }
        val request = githubRequest(
            githubApiUrl("repos", repository.owner, repository.name, "zipball", commitSha.lowercase()),
        )
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

    private fun githubApiUrl(vararg segments: String): String {
        val builder = "https://api.github.com".toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private fun githubStatusMessage(code: Int): String = when (code) {
        403, 429 -> "GitHub rate-limited or denied this public repository request. Try again later."
        404 -> "GitHub could not provide the archive for the resolved commit."
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
    fun deploy(
        repositoryUrl: String,
        websiteName: String,
        publishDirectory: String = "",
        selectedRef: String = "",
    ): LocalSite {
        val repository = PublicGithubRepository.parse(repositoryUrl)
        val name = websiteName.trim()
        require(name.length in 2..80) { "Website name must be 2 to 80 characters." }
        val requestedRef = GithubDeploymentRef.validateSelected(selectedRef)
        store.recordEvent(null, "deployment.started", "GitHub ${repository.fullName}")

        cleanupStaleArchives()
        val archive = File(context.cacheDir, "anb-github-${UUID.randomUUID()}.zip")
        var site: LocalSite? = null
        var archiveInstalled = false
        try {
            val metadata = github.repository(repository)
            require(!metadata.isPrivate) { "Private GitHub repositories are not supported yet." }
            val ref = GithubDeploymentRef.resolveSelectedOrDefault(requestedRef, metadata.defaultBranch)
            val commitSha = github.resolveCommit(repository, ref)
            store.recordEvent(null, "deployment.commit_resolved", "GitHub ${repository.fullName} · $ref · $commitSha")

            github.downloadCommit(repository, commitSha, archive)
            store.recordEvent(null, "deployment.archive_downloaded", "GitHub ${repository.fullName} · ${archive.length()} bytes")

            site = store.createSite(name)
            files.installGithubArchive(site.id, archive.inputStream(), publishDirectory)
            archiveInstalled = true
            store.recordEvent(
                site.id,
                "deployment.succeeded",
                "GitHub ${repository.fullName} · $ref · $commitSha · ${publishDirectory.ifBlank { "/" }}",
            )
            return site
        } catch (failure: Exception) {
            if (!archiveInstalled && site != null) runCatching { store.deleteSite(site.id) }
            runCatching {
                store.recordEvent(
                    site?.id,
                    "deployment.failed",
                    "GitHub ${repository.fullName} · ${requestedRef.ifBlank { "default branch" }} · ${(failure.message ?: "Deployment failed").take(300)}",
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
