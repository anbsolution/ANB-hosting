package com.anbhosting.app

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class LocalManagedDatabase(
    val name: String,
    val sizeBytes: Long,
)

data class LocalSqlResult(
    val columns: List<String>,
    val rows: List<List<String>>,
    val message: String,
    val truncated: Boolean = false,
    val columnsTruncated: Boolean = false,
)

/** Private per-website SQLite databases, stored outside static website roots. */
class LocalDatabaseManager(
    context: Context,
    private val store: LocalHostingStore,
) {
    private val appContext = context.applicationContext
    private val databaseRoot = File(appContext.noBackupFilesDir, DATABASE_DIRECTORY).canonicalFile

    fun list(siteId: String): List<LocalManagedDatabase> {
        val directory = siteDirectory(siteId)
        if (!directory.exists()) return emptyList()
        check(!directory.isFile && !directory.isSymbolicLink()) { "Database storage is invalid." }
        return (directory.listFiles() ?: throw IOException("Database list could not be read."))
            .filter { file -> file.isFile && !file.isSymbolicLink() && file.extension.equals("db", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { file -> LocalManagedDatabase(file.nameWithoutExtension, file.length()) }
    }

    fun create(siteId: String, name: String) {
        val file = databaseFile(siteId, name)
        check(!file.exists()) { "A database with this name already exists." }
        val parent = file.parentFile ?: throw IOException("Database storage is invalid.")
        check(parent.isDirectory || parent.mkdirs()) { "Database storage could not be created." }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE $INTERNAL_TABLE (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
            )
        }
        store.recordEvent(siteId, "database.created", name)
    }

    fun delete(siteId: String, name: String) {
        val file = databaseFile(siteId, name)
        check(file.isFile && !file.isSymbolicLink()) { "This database no longer exists." }
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { sidecar ->
            if (sidecar.exists()) check(sidecar.delete()) { "Database files could not be removed." }
        }
        store.recordEvent(siteId, "database.deleted", name)
    }

    fun deleteAllForSite(siteId: String) {
        val directory = siteDirectory(siteId)
        if (directory.exists()) check(directory.deleteRecursively()) { "Website databases could not be removed." }
    }

    /** Runs one bounded SELECT or one supported write/schema statement. */
    fun execute(siteId: String, name: String, rawStatement: String): LocalSqlResult {
        val statement = validateStatement(rawStatement)
        val file = databaseFile(siteId, name)
        check(file.isFile && !file.isSymbolicLink()) { "This database no longer exists." }
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            if (SELECT_PATTERN.containsMatchIn(statement)) {
                val result = database.rawQuery(statement, null).use { cursor -> cursor.toSqlResult() }
                store.recordEvent(siteId, "database.query", "SELECT")
                return result
            }
            database.beginTransaction()
            try {
                database.execSQL(statement)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            val verb = SQL_VERB_PATTERN.find(statement)?.groupValues?.get(1)?.uppercase().orEmpty()
            store.recordEvent(siteId, "database.query", verb)
            return LocalSqlResult(emptyList(), emptyList(), "Statement executed successfully.")
        } finally {
            database.close()
        }
    }

    fun export(siteId: String, name: String, destination: OutputStream) {
        val file = databaseFile(siteId, name)
        check(file.isFile && !file.isSymbolicLink()) { "This database no longer exists." }
        destination.use { output ->
            file.inputStream().use { input -> copyBounded(input, output, MAX_DATABASE_BYTES) }
        }
        store.recordEvent(siteId, "database.exported", name)
    }

    /** Imports an SQLite file under a new name without overwriting an existing database. */
    fun importDatabase(siteId: String, name: String, source: InputStream) {
        val destination = databaseFile(siteId, name)
        check(!destination.exists()) { "A database with this name already exists. Delete it before restoring this file." }
        val parent = destination.parentFile ?: throw IOException("Database storage is invalid.")
        check(parent.isDirectory || parent.mkdirs()) { "Database storage could not be created." }
        val temporary = File(parent, ".anb-db-import-${UUID.randomUUID()}")
        try {
            source.use { input -> temporary.outputStream().use { output -> copyBounded(input, output, MAX_DATABASE_BYTES) } }
            val signature = ByteArray(SQLITE_SIGNATURE.size)
            val signatureBytes = runCatching {
                temporary.inputStream().use { input -> DataInputStream(input).readFully(signature); signature.size }
            }.getOrDefault(0)
            require(signatureBytes == SQLITE_SIGNATURE.size && signature.contentEquals(SQLITE_SIGNATURE)) {
                "Selected file is not a SQLite database."
            }
            val verified = SQLiteDatabase.openDatabase(temporary.path, null, SQLiteDatabase.OPEN_READONLY)
            verified.close()
            if (!temporary.renameTo(destination)) throw IOException("Imported database could not be saved.")
        } finally {
            temporary.delete()
        }
        store.recordEvent(siteId, "database.imported", name)
    }

    private fun Cursor.toSqlResult(): LocalSqlResult {
        val visibleColumns = columnNames.take(MAX_RESULT_COLUMNS)
        val rows = ArrayList<List<String>>(MAX_RESULT_ROWS)
        var truncated = false
        while (moveToNext()) {
            if (rows.size >= MAX_RESULT_ROWS) {
                truncated = true
                break
            }
            rows += visibleColumns.indices.map { index ->
                when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "NULL"
                    Cursor.FIELD_TYPE_BLOB -> "[BLOB]"
                    else -> getString(index).orEmpty().take(MAX_CELL_CHARACTERS)
                }
            }
        }
        val message = if (rows.isEmpty()) "Query returned no rows." else "${rows.size} rows returned."
        return LocalSqlResult(
            columns = visibleColumns,
            rows = rows,
            message = message,
            truncated = truncated,
            columnsTruncated = columnNames.size > MAX_RESULT_COLUMNS,
        )
    }

    private fun validateStatement(rawStatement: String): String {
        require(rawStatement.length <= MAX_SQL_CHARACTERS) { "SQL is limited to 8,000 characters." }
        val statement = rawStatement.trim().removeSuffix(";").trim()
        require(statement.isNotBlank()) { "Enter a SQL statement." }
        require(';' !in statement && !statement.contains("--") && !statement.contains("/*") && !statement.contains("*/")) {
            "Run one SQL statement at a time; comments and multiple statements are not supported."
        }
        require(!statement.contains(INTERNAL_TABLE, ignoreCase = true)) { "The ANB metadata table is reserved." }
        require(SELECT_PATTERN.containsMatchIn(statement) || WRITE_PATTERNS.any { it.containsMatchIn(statement) }) {
            "Supported statements: SELECT, INSERT, UPDATE, DELETE, CREATE TABLE, DROP TABLE, and ALTER TABLE."
        }
        return statement
    }

    private fun databaseFile(siteId: String, name: String): File {
        validateName(name)
        val directory = siteDirectory(siteId)
        val file = File(directory, "$name.db").canonicalFile
        check(file.parentFile == directory.canonicalFile) { "Database path is invalid." }
        return file
    }

    private fun siteDirectory(siteId: String): File {
        require(SITE_ID_PATTERN.matches(siteId)) { "Website ID is invalid." }
        check(store.findSite(siteId) != null) { "Website no longer exists." }
        val directory = File(databaseRoot, siteId).canonicalFile
        check(directory.path.startsWith(databaseRoot.path + File.separator)) { "Database storage path is invalid." }
        return directory
    }

    private fun validateName(name: String) {
        require(DATABASE_NAME_PATTERN.matches(name)) {
            "Database names must start with a letter and use up to 40 letters, numbers, underscores, or hyphens."
        }
    }

    private fun File.isSymbolicLink(): Boolean = java.nio.file.Files.isSymbolicLink(toPath())

    private fun copyBounded(input: InputStream, output: OutputStream, maximumBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (copied > maximumBytes - count) throw IOException("Database files are limited to 200 MiB.")
            output.write(buffer, 0, count)
            copied += count
        }
    }

    companion object {
        private const val DATABASE_DIRECTORY = "anb-local-databases"
        private const val INTERNAL_TABLE = "__anbhosting_meta"
        private const val MAX_DATABASE_BYTES = 200L * 1024 * 1024
        private const val MAX_SQL_CHARACTERS = 8_000
        private const val MAX_RESULT_ROWS = 100
        private const val MAX_RESULT_COLUMNS = 30
        private const val MAX_CELL_CHARACTERS = 2_000
        private val SQLITE_SIGNATURE = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val SITE_ID_PATTERN = Regex("^[0-9a-fA-F-]{36}$")
        private val DATABASE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")
        private val SELECT_PATTERN = Regex("^\\s*SELECT\\b", RegexOption.IGNORE_CASE)
        private val SQL_VERB_PATTERN = Regex("^\\s*([A-Za-z]+)")
        private val WRITE_PATTERNS = listOf(
            Regex("^\\s*INSERT\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*UPDATE\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*DELETE\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*CREATE\\s+TABLE\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*DROP\\s+TABLE\\b", RegexOption.IGNORE_CASE),
            Regex("^\\s*ALTER\\s+TABLE\\b", RegexOption.IGNORE_CASE),
        )
    }
}
