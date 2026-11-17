package com.anbhosting.app

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class LocalFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

data class LocalSiteUsage(
    val storedBytes: Long,
    val fileCount: Int,
    val trafficLast30DaysBytes: Long,
)

/** File operations stay inside a website's app-private root. */
class LocalFileManager(private val store: LocalHostingStore) {
    fun measureUsage(site: LocalSite): LocalSiteUsage {
        val root = site.root.canonicalFile
        check(root.parentFile == store.siteRoot(site.slug).parentFile?.canonicalFile) {
            "Website storage path is invalid."
        }
        var bytes = 0L
        var files = 0
        root.walkTopDown().forEach { file ->
            if (file == root || Files.isSymbolicLink(file.toPath()) || !file.isFile) return@forEach
            bytes = if (Long.MAX_VALUE - bytes < file.length()) Long.MAX_VALUE else bytes + file.length()
            if (files < Int.MAX_VALUE) files += 1
        }
        return LocalSiteUsage(bytes, files, store.trafficLast30Days(site.id))
    }

    fun list(siteId: String, relativeDirectory: String = ""): List<LocalFileEntry> {
        val root = siteRoot(siteId)
        val directory = resolve(root, relativeDirectory)
        require(directory.isDirectory) { "This folder does not exist." }
        return (directory.listFiles() ?: throw IOException("Folder could not be read."))
            .filterNot {
                it.name.startsWith(".anb-tmp-") ||
                    it.name.startsWith(".anb-restore-") ||
                    it.name.startsWith(".anb-restore-old-")
            }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { file ->
                LocalFileEntry(
                    name = file.name,
                    relativePath = relative(root, file),
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    modifiedAt = file.lastModified(),
                )
            }
    }

    fun readText(siteId: String, relativePath: String): String {
        val file = resolve(siteRoot(siteId), relativePath)
        require(file.isFile) { "Select a file to edit." }
        require(file.length() <= MAX_TEXT_BYTES) { "Text files are limited to 1 MiB." }
        require(isTextFile(file.name)) { "This file type cannot be opened in the text editor." }
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun createTextFile(siteId: String, relativePath: String) {
        val file = resolve(siteRoot(siteId), relativePath)
        check(!file.exists()) { "A file or folder already uses this name." }
        writeText(siteId, relativePath, "")
    }

    fun writeText(siteId: String, relativePath: String, contents: String) {
        val root = siteRoot(siteId)
        val file = resolve(root, relativePath)
        require(file != root) { "Choose a file name." }
        require(isTextFile(file.name)) { "Use an HTML, CSS, JavaScript, Python, JSON, XML, or text file." }
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Text files are limited to 1 MiB." }
        check(file.parentFile?.isDirectory == true) { "The parent folder does not exist." }
        check(!file.exists() || file.isFile) { "A folder already uses this name." }
        val temporary = File(file.parentFile, ".anb-tmp-${UUID.randomUUID()}")
        val previous = File(file.parentFile, ".anb-tmp-old-${UUID.randomUUID()}")
        try {
            temporary.writeBytes(bytes)
            if (file.exists() && !file.renameTo(previous)) throw IOException("The old file could not be staged for replacement.")
            if (!temporary.renameTo(file)) {
                if (previous.exists() && !previous.renameTo(file)) {
                    throw IOException("The edited file could not be saved; the previous copy is at ${previous.name}.")
                }
                throw IOException("The edited file could not be saved.")
            }
            previous.delete()
        } finally {
            temporary.delete()
        }
        store.recordEvent(siteId, "file.edited", relative(root, file))
    }

    fun createDirectory(siteId: String, relativePath: String) {
        val root = siteRoot(siteId)
        val directory = resolve(root, relativePath)
        require(directory != root) { "Choose a folder name." }
        check(directory.parentFile?.isDirectory == true) { "The parent folder does not exist." }
        check(directory.mkdir()) { "Folder could not be created; the name may already be in use." }
        store.recordEvent(siteId, "file.folder_created", relative(root, directory))
    }

    fun importFile(siteId: String, relativePath: String, source: InputStream) {
        val root = siteRoot(siteId)
        val destination = resolve(root, relativePath)
        require(destination != root) { "Choose a file name." }
        check(destination.parentFile?.isDirectory == true) { "The parent folder does not exist." }
        check(!destination.exists()) { "A file or folder already uses this name." }
        val temporary = File(destination.parentFile, ".anb-tmp-${UUID.randomUUID()}")
        try {
            source.use { input -> temporary.outputStream().use { output -> copyBounded(input, output, MAX_IMPORT_BYTES) } }
            if (!temporary.renameTo(destination)) throw IOException("The imported file could not be saved.")
        } finally {
            temporary.delete()
        }
        store.recordEvent(siteId, "file.uploaded", relative(root, destination))
    }

    fun exportFile(siteId: String, relativePath: String, destination: OutputStream) {
        val file = resolve(siteRoot(siteId), relativePath)
        require(file.isFile) { "Select a file to download." }
        file.inputStream().use { input -> destination.use { output -> input.copyTo(output) } }
        store.recordEvent(siteId, "file.downloaded", relativePath)
    }

    fun rename(siteId: String, relativePath: String, newName: String) {
        val root = siteRoot(siteId)
        val source = resolve(root, relativePath)
        require(source != root && source.exists()) { "This file or folder no longer exists." }
        validateLeafName(newName)
        val destination = File(source.parentFile, newName)
        check(!destination.exists()) { "A file or folder already uses this name." }
        if (!source.renameTo(destination)) throw IOException("Rename failed.")
        store.recordEvent(siteId, "file.renamed", "${relative(root, source)} → ${relative(root, destination)}")
    }

    fun delete(siteId: String, relativePath: String) {
        val root = siteRoot(siteId)
        val target = resolve(root, relativePath)
        require(target != root && target.exists()) { "This file or folder no longer exists." }
        check(target.deleteRecursively()) { "File or folder could not be deleted." }
        store.recordEvent(siteId, "file.deleted", relative(root, target))
    }

    /** Extracts an uploaded archive into a new folder; existing files are never overwritten. */
    fun extractZip(siteId: String, zipPath: String, targetDirectory: String) {
        val root = siteRoot(siteId)
        val archive = resolve(root, zipPath)
        require(archive.isFile && archive.extension.equals("zip", ignoreCase = true)) {
            "Select a ZIP archive to extract."
        }
        val target = resolve(root, targetDirectory)
        require(target != root) { "Choose a destination folder name." }
        check(!target.exists()) { "The destination folder already exists." }
        val parent = target.parentFile ?: throw IOException("Destination folder is invalid.")
        check(parent.isDirectory) { "The destination's parent folder does not exist." }
        val staging = File(parent, ".anb-tmp-${UUID.randomUUID()}")
        try {
            check(staging.mkdir()) { "Temporary storage could not be created." }
            archive.inputStream().use { input -> extractZipContents(input, staging) }
            if (!staging.renameTo(target)) throw IOException("Extracted files could not be moved into place.")
        } finally {
            staging.deleteRecursively()
        }
        store.recordEvent(siteId, "file.zip_extracted", relative(root, target))
    }

    fun createBackup(siteId: String, destination: OutputStream) {
        val root = siteRoot(siteId)
        var total = 0L
        ZipOutputStream(destination).use { zip ->
            root.walkTopDown().filter { it != root }.forEach { file ->
                checkInside(root, file)
                if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic links are not included in backups.")
                if (file.isDirectory) return@forEach
                check(file.isFile) { "Unsupported file in website storage." }
                val path = relative(root, file)
                if (path.startsWith(".anb-tmp-") || path.contains("/.anb-tmp-")) return@forEach
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { input ->
                    total += copyBounded(input, zip, MAX_ARCHIVE_BYTES - total)
                }
                zip.closeEntry()
            }
        }
        store.recordEvent(siteId, "website.backup_created", "${total} bytes")
    }

    /** Restores a validated ZIP atomically, replacing the site's current files. */
    fun restoreBackup(siteId: String, source: InputStream) {
        val root = siteRoot(siteId)
        val parent = root.parentFile ?: throw IOException("Website storage is invalid.")
        val staging = File(parent, ".anb-restore-${UUID.randomUUID()}")
        val displaced = File(parent, ".anb-restore-old-${UUID.randomUUID()}")
        try {
            check(staging.mkdir()) { "Temporary storage could not be created." }
            source.use { extractZipContents(it, staging) }
            if (!root.renameTo(displaced)) throw IOException("Current website files could not be staged for restore.")
            if (!staging.renameTo(root)) {
                if (!displaced.renameTo(root)) {
                    throw IOException("Restore failed; current files are retained in ${displaced.name}.")
                }
                throw IOException("The backup could not be restored; current files were retained.")
            }
            if (!displaced.deleteRecursively()) {
                store.recordEvent(siteId, "website.restore_cleanup_needed", displaced.name)
            }
        } finally {
            staging.deleteRecursively()
        }
        store.recordEvent(siteId, "website.restored", "Backup restored")
    }

    /** Atomically installs a prebuilt static folder from a GitHub ZIP archive into a stopped site. */
    fun installGithubArchive(siteId: String, source: InputStream, publishDirectory: String = "") {
        val site = store.listSites().firstOrNull { it.id == siteId }
            ?: throw IllegalArgumentException("Website no longer exists.")
        check(site.status == LocalHostingStore.STATUS_STOPPED) {
            "Stop the website before replacing its files."
        }

        val root = site.root.canonicalFile
        val parent = root.parentFile ?: throw IOException("Website storage is invalid.")
        val extracted = File(parent, ".anb-tmp-github-${UUID.randomUUID()}")
        val prepared = File(parent, ".anb-tmp-publish-${UUID.randomUUID()}")
        val displaced = File(parent, ".anb-restore-old-${UUID.randomUUID()}")
        try {
            check(extracted.mkdir()) { "Temporary deployment storage could not be created." }
            source.use { extractZipContents(it, extracted) }

            val archiveEntries = extracted.listFiles() ?: throw IOException("GitHub archive could not be read.")
            require(archiveEntries.isNotEmpty()) { "GitHub repository archive is empty." }
            val repositoryRoot = if (archiveEntries.size == 1 && archiveEntries[0].isDirectory) {
                archiveEntries[0]
            } else {
                extracted
            }
            val requestedDirectory = normalizePublishDirectory(publishDirectory)
            val selectedDirectory = resolve(repositoryRoot, requestedDirectory)
            require(selectedDirectory.isDirectory) { "The selected publish folder does not exist in the repository." }
            require(File(selectedDirectory, "index.html").isFile) {
                "The selected publish folder must contain a prebuilt index.html file. Builds are not run during this deployment."
            }

            copyDirectoryContents(selectedDirectory, prepared)
            check(File(prepared, "index.html").isFile) { "The static website could not be staged." }
            if (!root.renameTo(displaced)) throw IOException("Current website files could not be staged for deployment.")
            if (!prepared.renameTo(root)) {
                if (!displaced.renameTo(root)) {
                    throw IOException("Deployment failed; previous files remain in ${displaced.name}.")
                }
                throw IOException("The GitHub website files could not be published; previous files were restored.")
            }
            if (!displaced.deleteRecursively()) {
                store.recordEvent(siteId, "deployment.cleanup_needed", displaced.name)
            }
        } finally {
            extracted.deleteRecursively()
            prepared.deleteRecursively()
            if (!root.exists() && displaced.exists()) displaced.renameTo(root)
        }
        store.recordEvent(
            siteId,
            "deployment.files_installed",
            normalizePublishDirectory(publishDirectory).ifBlank { "/" },
        )
    }

    private fun extractZipContents(source: InputStream, staging: File) {
        val seen = mutableSetOf<String>()
        var entries = 0
        var totalBytes = 0L
        val checkedSource = PushbackInputStream(source.buffered(), 4)
        val signature = ByteArray(4)
        var signatureSize = 0
        while (signatureSize < signature.size) {
            val count = checkedSource.read(signature, signatureSize, signature.size - signatureSize)
            if (count < 0) break
            signatureSize += count
        }
        val isZip = signatureSize == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte() &&
            ((signature[2] == 3.toByte() && signature[3] == 4.toByte()) ||
                (signature[2] == 5.toByte() && signature[3] == 6.toByte()) ||
                (signature[2] == 7.toByte() && signature[3] == 8.toByte()))
        require(isZip) { "The selected file is not a valid ZIP backup." }
        checkedSource.unread(signature, 0, signatureSize)
        ZipInputStream(checkedSource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ARCHIVE_ENTRIES) { "ZIP archive contains too many entries." }
                val path = validateArchivePath(entry.name)
                require(seen.add(path)) { "ZIP archive contains duplicate paths." }
                val output = File(staging, path).canonicalFile
                val stagingRoot = staging.canonicalFile
                require(output.path.startsWith(stagingRoot.path + File.separator)) {
                    "ZIP archive contains an unsafe path."
                }
                if (entry.isDirectory || entry.name.endsWith('/')) {
                    check(output.isDirectory || output.mkdirs()) { "Archive folder could not be created." }
                    require(zip.read() < 0) { "ZIP folder entries must not contain file data." }
                } else {
                    check(output.parentFile?.isDirectory == true || output.parentFile?.mkdirs() == true) {
                        "Archive folder could not be created."
                    }
                    check(!output.exists()) { "ZIP archive contains conflicting paths." }
                    output.outputStream().use { target ->
                        totalBytes += copyBounded(zip, target, MAX_ARCHIVE_BYTES - totalBytes)
                    }
                }
                require(totalBytes <= MAX_ARCHIVE_BYTES) { "Uncompressed ZIP content is limited to 200 MiB." }
                zip.closeEntry()
            }
        }
    }

    private fun siteRoot(siteId: String): File {
        val site = store.listSites().firstOrNull { it.id == siteId }
            ?: throw IllegalArgumentException("Website no longer exists.")
        val root = site.root.canonicalFile
        check(root.parentFile == store.siteRoot(site.slug).parentFile?.canonicalFile) {
            "Website storage path is invalid."
        }
        return root
    }

    private fun resolve(root: File, relativePath: String): File {
        require(relativePath.length <= MAX_PATH_LENGTH) { "Path is too long." }
        require(!relativePath.startsWith('/') && !relativePath.contains('\\') && !relativePath.contains('\u0000')) {
            "Invalid website path."
        }
        val parts = relativePath.split('/').filter(String::isNotEmpty)
        require(parts.none { it == "." || it == ".." }) { "Invalid website path." }
        val canonicalRoot = root.canonicalFile
        val result = if (parts.isEmpty()) canonicalRoot else File(canonicalRoot, parts.joinToString(File.separator)).canonicalFile
        checkInside(canonicalRoot, result)
        return result
    }

    private fun validateArchivePath(path: String): String {
        require(path.isNotBlank() && path.length <= MAX_PATH_LENGTH) { "ZIP archive contains an invalid path." }
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) {
            "ZIP archive contains an unsafe path."
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "ZIP archive contains an unsafe path." }
        val parts = path.removeSuffix("/").split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "ZIP archive contains an unsafe path."
        }
        return parts.joinToString("/")
    }

    private fun normalizePublishDirectory(value: String): String {
        val path = value.trim()
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) {
            "Publish folder must be a relative website path."
        }
        val normalized = path.trimEnd('/')
        return if (normalized.isEmpty() || normalized == ".") "" else normalized
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        check(destination.mkdir()) { "Website files could not be staged." }
        var copiedBytes = 0L
        source.walkTopDown().forEach { sourceFile ->
            checkInside(source, sourceFile.canonicalFile)
            if (Files.isSymbolicLink(sourceFile.toPath())) {
                throw IOException("Symbolic links are not allowed in a GitHub deployment archive.")
            }
            if (sourceFile == source) return@forEach

            val relativePath = relative(source, sourceFile)
            val destinationFile = File(destination, relativePath).canonicalFile
            checkInside(destination.canonicalFile, destinationFile)
            if (sourceFile.isDirectory) {
                check(destinationFile.isDirectory || destinationFile.mkdirs()) {
                    "A deployment folder could not be created."
                }
            } else {
                check(sourceFile.isFile && destinationFile.parentFile?.isDirectory == true) {
                    "The GitHub archive contains an unsupported file."
                }
                sourceFile.inputStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        copiedBytes += copyBounded(input, output, MAX_ARCHIVE_BYTES - copiedBytes)
                    }
                }
            }
        }
    }

    private fun validateLeafName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Name must be 1 to 120 characters." }
        require(name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "Enter a valid file or folder name."
        }
    }

    private fun relative(root: File, file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun checkInside(root: File, candidate: File) {
        check(candidate == root || candidate.path.startsWith(root.path + File.separator)) {
            "Path is outside website storage."
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        require(limit >= 0) { "File exceeds the size limit." }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= limit) { "File exceeds the size limit." }
            output.write(buffer, 0, count)
        }
        return copied
    }

    private fun isTextFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    companion object {
        private const val MAX_PATH_LENGTH = 512
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_TEXT_BYTES = 1024 * 1024L
        private const val MAX_IMPORT_BYTES = 100 * 1024 * 1024L
        private const val MAX_ARCHIVE_BYTES = 200 * 1024 * 1024L
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private val TEXT_EXTENSIONS = setOf("html", "htm", "css", "js", "mjs", "py", "json", "txt", "xml", "svg", "md", "yml", "yaml")
    }
}
