package com.anbhosting.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDate
import java.util.UUID

data class LocalSite(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val runtime: String,
    val createdAt: Long,
    val root: File,
) {
    /** Reserved loopback hostname for this phone's offline preview. */
    val localHostname: String get() = "$slug.localhost"
}

data class LocalHostingEvent(
    val action: String,
    val detail: String,
    val createdAt: Long,
)

data class LocalHttpRequestLog(
    val id: Long,
    val siteId: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val responseBytes: Long,
    val errorMessage: String,
    val createdAt: Long,
) {
    val isError: Boolean get() = statusCode == 0 || statusCode >= 400 || errorMessage.isNotBlank()
}

data class LocalHttpRequestSummary(
    val requestsLast24Hours: Long,
    val errorsLast24Hours: Long,
)

data class LocalHostAlias(
    val id: String,
    val siteId: String,
    val hostname: String,
    val createdAt: Long,
)

data class LocalDeployment(
    val id: String,
    val siteId: String,
    val repository: String,
    val ref: String,
    val commitSha: String,
    val backupFileName: String,
    val status: String,
    val createdAt: Long,
)

/** App-private SQLite metadata and website storage for offline hosting. */
class LocalHostingStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val appContext = context.applicationContext
    private val sitesDirectory = File(appContext.filesDir, "local-sites")
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sites (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                slug TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                runtime TEXT NOT NULL DEFAULT 'STATIC',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site_id TEXT,
                action TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX events_created_at_idx ON events(created_at DESC)")
        createTrafficTable(db)
        createDeploymentTable(db)
        createLocalHostAliasTable(db)
        createHttpRequestLogTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTrafficTable(db)
        if (oldVersion < 3) createDeploymentTable(db)
        if (oldVersion < 4) createLocalHostAliasTable(db)
        if (oldVersion < 5) createHttpRequestLogTable(db)
        if (oldVersion < 6 && tableExists(db, "sites")) {
            db.execSQL("ALTER TABLE sites ADD COLUMN runtime TEXT NOT NULL DEFAULT 'STATIC'")
        }
    }

    fun listSites(): List<LocalSite> {
        val result = mutableListOf<LocalSite>()
        readableDatabase.query(
            "sites",
            arrayOf("id", "name", "slug", "status", "created_at", "runtime"),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toLocalSite()
        }
        return result
    }

    fun findSiteBySlug(slug: String): LocalSite? = readableDatabase.query(
        "sites",
        arrayOf("id", "name", "slug", "status", "created_at", "runtime"),
        "slug = ?",
        arrayOf(slug),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toLocalSite() else null }

    fun findSite(id: String): LocalSite? = readableDatabase.query(
        "sites",
        arrayOf("id", "name", "slug", "status", "created_at", "runtime"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toLocalSite() else null }

    fun createSite(rawName: String, runtime: String = RUNTIME_STATIC): LocalSite {
        val name = rawName.trim()
        require(name.length in 2..80) { "Website name must be 2 to 80 characters." }
        require(runtime == RUNTIME_STATIC || runtime == RUNTIME_PYTHON) { "Choose a supported website runtime." }
        check(sitesDirectory.exists() || sitesDirectory.mkdirs()) { "Website storage could not be created." }

        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "site" }
            .take(36)
            .trimEnd('-')
        var id = UUID.randomUUID().toString()
        var slug = "$base-${id.take(8)}"
        while (findSiteBySlug(slug) != null || localHostAliasExists("$slug.localhost")) {
            id = UUID.randomUUID().toString()
            slug = "$base-${id.take(8)}"
        }
        val root = File(sitesDirectory, slug)
        check(root.mkdirs()) { "Website folder could not be created." }

        try {
            val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
            if (runtime == RUNTIME_PYTHON) {
                File(root, "main.py").writeText(
                    """def handle_request(request):
    return {
        "status": 200,
        "content_type": "text/html; charset=utf-8",
        "body": "<!doctype html><html><body><h1>Hello from Python</h1><p>Path: " + request["path"] + "</p></body></html>",
    }
""".trimIndent(),
                    Charsets.UTF_8,
                )
            } else {
                File(root, "index.html").writeText(
                    """<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>$safeName</title></head>
<body><main><h1>$safeName</h1><p>This starter page is ready for local preview.</p></main></body>
</html>
""".trimIndent(),
                    Charsets.UTF_8,
                )
            }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("id", id)
                put("name", name)
                put("slug", slug)
                put("status", STATUS_STOPPED)
                put("runtime", runtime)
                put("created_at", now)
                put("updated_at", now)
            }
            check(writableDatabase.insertOrThrow("sites", null, values) != -1L) {
                "Website could not be saved."
            }
            recordEvent(id, "website.created", name)
            return LocalSite(id, name, slug, STATUS_STOPPED, runtime, now, root)
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }

    fun setSiteStatus(id: String, status: String) {
        require(status == STATUS_ACTIVE || status == STATUS_STOPPED)
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        val changed = writableDatabase.update("sites", values, "id = ?", arrayOf(id))
        check(changed == 1) { "Website no longer exists." }
        recordEvent(id, if (status == STATUS_ACTIVE) "website.started" else "website.stopped", "")
    }

    fun deleteSite(id: String) {
        val slug = readableDatabase.query(
            "sites",
            arrayOf("slug"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getString(0)
        }
        val root = siteRoot(slug)
        check(root.canonicalFile.parentFile == sitesDirectory.canonicalFile) {
            "Website folder is outside local storage."
        }
        check(!root.exists() || root.deleteRecursively()) { "Website files could not be deleted." }
        val backupFileNames = readableDatabase.query(
            "deployments",
            arrayOf("backup_file"),
            "site_id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        writableDatabase.delete("sites", "id = ?", arrayOf(id))
        writableDatabase.delete("local_host_aliases", "site_id = ?", arrayOf(id))
        writableDatabase.delete("daily_traffic", "site_id = ?", arrayOf(id))
        writableDatabase.delete("deployments", "site_id = ?", arrayOf(id))
        writableDatabase.delete("http_request_logs", "site_id = ?", arrayOf(id))
        backupFileNames.forEach { backupName ->
            if (backupName.matches(Regex("deploy-[a-f0-9-]{36}\\.zip"))) {
                File(appContext.filesDir, "github-deployment-backups/$backupName").delete()
            }
        }
        recordEvent(id, "website.deleted", slug)
    }

    fun listLocalHostAliases(siteId: String): List<LocalHostAlias> {
        val aliases = mutableListOf<LocalHostAlias>()
        readableDatabase.query(
            "local_host_aliases",
            arrayOf("id", "site_id", "hostname", "created_at"),
            "site_id = ?",
            arrayOf(siteId),
            null,
            null,
            "hostname COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                aliases += LocalHostAlias(
                    id = cursor.getString(0),
                    siteId = cursor.getString(1),
                    hostname = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                )
            }
        }
        return aliases
    }

    /** Create an additional offline hostname under the reserved .localhost domain. */
    fun createLocalHostAlias(siteId: String, rawLabel: String): LocalHostAlias {
        val site = findSite(siteId) ?: error("Website no longer exists.")
        val label = rawLabel.trim().lowercase()
        require(label.length in 1..60 && LOCAL_HOST_LABEL.matches(label)) {
            "Enter a single .localhost label using letters, numbers, and interior hyphens."
        }
        require(label != "localhost") { "Use a different .localhost label." }
        require(label != site.slug) { "This website already has that default hostname." }
        check(findSiteBySlug(label) == null) { "That hostname belongs to another website." }

        val alias = LocalHostAlias(
            id = UUID.randomUUID().toString(),
            siteId = siteId,
            hostname = "$label.localhost",
            createdAt = System.currentTimeMillis(),
        )
        try {
            writableDatabase.insertOrThrow(
                "local_host_aliases",
                null,
                ContentValues().apply {
                    put("id", alias.id)
                    put("site_id", alias.siteId)
                    put("hostname", alias.hostname)
                    put("created_at", alias.createdAt)
                },
            )
        } catch (_: SQLiteConstraintException) {
            throw IllegalArgumentException("That .localhost hostname is already assigned.")
        }
        recordEvent(siteId, "domain.local_created", alias.hostname)
        return alias
    }

    fun deleteLocalHostAlias(siteId: String, aliasId: String) {
        val alias = readableDatabase.query(
            "local_host_aliases",
            arrayOf("hostname"),
            "id = ? AND site_id = ?",
            arrayOf(aliasId, siteId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getString(0)
        }
        val removed = writableDatabase.delete(
            "local_host_aliases",
            "id = ? AND site_id = ?",
            arrayOf(aliasId, siteId),
        )
        check(removed == 1) { "Local hostname no longer exists." }
        recordEvent(siteId, "domain.local_deleted", alias)
    }

    fun findSiteByLocalHostname(hostname: String): LocalSite? {
        val canonical = hostname.trimEnd('.').lowercase()
        val label = canonical.removeSuffix(".localhost")
        if (!canonical.endsWith(".localhost") || !LOCAL_HOST_LABEL.matches(label)) return null

        findSiteBySlug(label)?.let { return it }
        return readableDatabase.rawQuery(
            """SELECT sites.id, sites.name, sites.slug, sites.status, sites.created_at, sites.runtime
                FROM local_host_aliases
                JOIN sites ON sites.id = local_host_aliases.site_id
                WHERE local_host_aliases.hostname = ?
                LIMIT 1""".trimIndent(),
            arrayOf(canonical),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toLocalSite() else null }
    }

    private fun localHostAliasExists(hostname: String): Boolean = readableDatabase.query(
        "local_host_aliases",
        arrayOf("id"),
        "hostname = ?",
        arrayOf(hostname),
        null,
        null,
        null,
        "1",
    ).use { cursor -> cursor.moveToFirst() }

    fun latestAppliedDeployment(siteId: String): LocalDeployment? = readableDatabase.query(
        "deployments",
        DEPLOYMENT_COLUMNS,
        "site_id = ? AND status = ?",
        arrayOf(siteId, DEPLOYMENT_APPLIED),
        null,
        null,
        "created_at DESC, rowid DESC",
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toLocalDeployment() else null }

    fun deploymentHistory(siteId: String): List<LocalDeployment> = readableDatabase.query(
        "deployments",
        DEPLOYMENT_COLUMNS,
        "site_id = ?",
        arrayOf(siteId),
        null,
        null,
        "created_at DESC, rowid DESC",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toLocalDeployment()) } }

    fun recordDeployment(
        siteId: String,
        repository: String,
        ref: String,
        commitSha: String,
        backupFileName: String,
    ): LocalDeployment {
        require(repository.length in 3..200) { "Repository name is invalid." }
        require(ref.length in 1..255) { "Deployment reference is invalid." }
        require(commitSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Deployment commit SHA is invalid." }
        require(backupFileName.matches(Regex("deploy-[a-f0-9-]{36}\\.zip"))) { "Deployment backup name is invalid." }
        check(findSite(siteId) != null) { "Website no longer exists." }

        val deployment = LocalDeployment(
            id = UUID.randomUUID().toString(),
            siteId = siteId,
            repository = repository,
            ref = ref,
            commitSha = commitSha.lowercase(),
            backupFileName = backupFileName,
            status = DEPLOYMENT_APPLIED,
            createdAt = System.currentTimeMillis(),
        )
        val values = ContentValues().apply {
            put("id", deployment.id)
            put("site_id", deployment.siteId)
            put("repository", deployment.repository)
            put("ref", deployment.ref)
            put("commit_sha", deployment.commitSha)
            put("backup_file", deployment.backupFileName)
            put("status", deployment.status)
            put("created_at", deployment.createdAt)
        }
        writableDatabase.insertOrThrow("deployments", null, values)
        return deployment
    }

    fun markDeploymentRolledBack(id: String) {
        val changed = writableDatabase.update(
            "deployments",
            ContentValues().apply { put("status", DEPLOYMENT_ROLLED_BACK) },
            "id = ? AND status = ?",
            arrayOf(id, DEPLOYMENT_APPLIED),
        )
        check(changed == 1) { "Deployment is no longer available to roll back." }
    }

    fun pruneDeploymentHistory(siteId: String, keepNewest: Int): List<String> {
        require(keepNewest in 1..20)
        val db = writableDatabase
        val removed = mutableListOf<Pair<String, String>>()
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT id, backup_file FROM deployments WHERE site_id = ? ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET ?",
                arrayOf(siteId, keepNewest.toString()),
            ).use { cursor -> while (cursor.moveToNext()) removed += cursor.getString(0) to cursor.getString(1) }
            removed.forEach { (id, _) -> db.delete("deployments", "id = ?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed.map { it.second }
    }

    fun recentEvents(limit: Int = 30): List<LocalHostingEvent> {
        val result = mutableListOf<LocalHostingEvent>()
        readableDatabase.query(
            "events",
            arrayOf("action", "detail", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 100).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalHostingEvent(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        return result
    }

    /** Store bounded per-site HTTP access and error history; query strings are excluded by the server. */
    fun recordHttpRequest(
        siteId: String,
        method: String,
        path: String,
        statusCode: Int,
        responseBytes: Long,
        errorMessage: String?,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val siteExists = db.query(
                "sites",
                arrayOf("id"),
                "id = ?",
                arrayOf(siteId),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }
            if (!siteExists) return

            db.insertOrThrow(
                "http_request_logs",
                null,
                ContentValues().apply {
                    put("site_id", siteId)
                    put("method", method.uppercase().take(16))
                    put("path", path.take(MAX_REQUEST_LOG_PATH_CHARS))
                    put("status_code", statusCode.coerceIn(0, 599))
                    put("response_bytes", responseBytes.coerceAtLeast(0))
                    put("error_message", errorMessage.orEmpty().take(MAX_REQUEST_LOG_ERROR_CHARS))
                    put("created_at", System.currentTimeMillis())
                },
            )
            db.execSQL(
                "DELETE FROM http_request_logs WHERE site_id = ? AND id NOT IN " +
                    "(SELECT id FROM http_request_logs WHERE site_id = ? ORDER BY id DESC LIMIT ?)",
                arrayOf(siteId, siteId, MAX_REQUEST_LOGS_PER_SITE),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun httpRequestSummary(siteId: String, nowMillis: Long = System.currentTimeMillis()): LocalHttpRequestSummary {
        val since = nowMillis - REQUEST_LOG_SUMMARY_WINDOW_MS
        return readableDatabase.rawQuery(
            """SELECT COUNT(*),
                SUM(CASE WHEN status_code = 0 OR status_code >= 400 OR error_message <> '' THEN 1 ELSE 0 END)
                FROM http_request_logs WHERE site_id = ? AND created_at >= ?""".trimIndent(),
            arrayOf(siteId, since.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) LocalHttpRequestSummary(cursor.getLong(0), cursor.getLong(1))
            else LocalHttpRequestSummary(0, 0)
        }
    }

    fun httpRequestLogs(
        siteId: String,
        errorsOnly: Boolean = false,
        beforeId: Long? = null,
        limit: Int = REQUEST_LOG_PAGE_SIZE,
    ): List<LocalHttpRequestLog> {
        val selection = buildList {
            add("site_id = ?")
            if (errorsOnly) add("(status_code = 0 OR status_code >= 400 OR error_message <> '')")
            if (beforeId != null) add("id < ?")
        }.joinToString(" AND ")
        val args = buildList {
            add(siteId)
            if (beforeId != null) add(beforeId.toString())
        }.toTypedArray()
        return readableDatabase.query(
            "http_request_logs",
            REQUEST_LOG_COLUMNS,
            selection,
            args,
            null,
            null,
            "id DESC",
            limit.coerceIn(1, MAX_REQUEST_LOGS_PER_SITE).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    LocalHttpRequestLog(
                        id = cursor.getLong(0),
                        siteId = cursor.getString(1),
                        method = cursor.getString(2),
                        path = cursor.getString(3),
                        statusCode = cursor.getInt(4),
                        responseBytes = cursor.getLong(5),
                        errorMessage = cursor.getString(6),
                        createdAt = cursor.getLong(7),
                    ),
                )
            }
        }
    }

    fun requestLogsCsv(siteId: String): String {
        val rows = httpRequestLogs(siteId, limit = MAX_REQUEST_LOGS_PER_SITE).asReversed()
        return buildString {
            appendLine("time,method,path,status,response_bytes,error")
            rows.forEach { row ->
                appendLine(
                    listOf(
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.createdAt)),
                        row.method,
                        row.path,
                        if (row.statusCode == 0) "connection-failed" else row.statusCode.toString(),
                        row.responseBytes.toString(),
                        row.errorMessage,
                    ).joinToString(",") { it.toSafeCsvCell() },
                )
            }
        }
    }

    private fun String.toSafeCsvCell(): String {
        val startsWithFormula = trimStart().firstOrNull()?.let { it == '=' || it == '+' || it == '-' || it == '@' } == true
        val spreadsheetSafe = if (startsWithFormula) "'$this" else this
        return "\"${spreadsheetSafe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
    }

    fun recordEvent(siteId: String?, action: String, detail: String) {
        val values = ContentValues().apply {
            if (siteId == null) putNull("site_id") else put("site_id", siteId)
            put("action", action.take(80))
            put("detail", detail.take(500))
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("events", null, values)
        writableDatabase.execSQL(
            "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 1000)",
        )
    }

    fun recordTraffic(siteId: String, bytes: Long) {
        if (bytes <= 0) return
        val day = LocalDate.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = db.compileStatement("UPDATE daily_traffic SET bytes = bytes + ? WHERE site_id = ? AND day = ?")
            try {
                update.bindLong(1, bytes)
                update.bindString(2, siteId)
                update.bindString(3, day)
                if (update.executeUpdateDelete() == 0) {
                    val values = ContentValues().apply {
                        put("site_id", siteId)
                        put("day", day)
                        put("bytes", bytes)
                    }
                    db.insertOrThrow("daily_traffic", null, values)
                }
            } finally {
                update.close()
            }
            db.delete("daily_traffic", "day < ?", arrayOf(LocalDate.now().minusDays(29).toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun trafficLast30Days(siteId: String): Long {
        val since = LocalDate.now().minusDays(29).toString()
        return readableDatabase.query(
            "daily_traffic",
            arrayOf("SUM(bytes)"),
            "site_id = ? AND day >= ?",
            arrayOf(siteId, since),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    fun isLanSharingEnabled(): Boolean = preferences.getBoolean(PREF_LAN_SHARING, false)

    fun setLanSharingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_LAN_SHARING, enabled).apply()
        recordEvent(null, "server.lan_sharing_changed", if (enabled) "enabled" else "disabled")
    }

    fun isKeepCpuAwakeEnabled(): Boolean = preferences.getBoolean(PREF_KEEP_CPU_AWAKE, true)

    fun setKeepCpuAwakeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_KEEP_CPU_AWAKE, enabled).apply()
        recordEvent(null, "server.screen_off_hosting_changed", if (enabled) "enabled" else "disabled")
    }

    fun siteRoot(slug: String): File {
        require(slug.matches(Regex("[a-z0-9-]{1,60}"))) { "Invalid website address." }
        return File(sitesDirectory, slug)
    }

    private fun android.database.Cursor.toLocalSite(): LocalSite {
        val slug = getString(2)
        return LocalSite(
            id = getString(0),
            name = getString(1),
            slug = slug,
            status = getString(3),
            runtime = getString(5),
            createdAt = getLong(4),
            root = siteRoot(slug),
        )
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun android.database.Cursor.toLocalDeployment(): LocalDeployment = LocalDeployment(
        id = getString(0),
        siteId = getString(1),
        repository = getString(2),
        ref = getString(3),
        commitSha = getString(4),
        backupFileName = getString(5),
        status = getString(6),
        createdAt = getLong(7),
    )

    private fun createTrafficTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE daily_traffic (
                site_id TEXT NOT NULL,
                day TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                PRIMARY KEY(site_id, day)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX daily_traffic_day_idx ON daily_traffic(day)")
    }

    private fun createDeploymentTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE deployments (
                id TEXT PRIMARY KEY NOT NULL,
                site_id TEXT NOT NULL,
                repository TEXT NOT NULL,
                ref TEXT NOT NULL,
                commit_sha TEXT NOT NULL,
                backup_file TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX deployments_site_created_idx ON deployments(site_id, created_at DESC)")
    }

    private fun createLocalHostAliasTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE local_host_aliases (
                id TEXT PRIMARY KEY NOT NULL,
                site_id TEXT NOT NULL,
                hostname TEXT NOT NULL COLLATE NOCASE UNIQUE,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX local_host_aliases_site_idx ON local_host_aliases(site_id, hostname)")
    }

    private fun createHttpRequestLogTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE http_request_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site_id TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                status_code INTEGER NOT NULL,
                response_bytes INTEGER NOT NULL,
                error_message TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX http_request_logs_site_id_idx ON http_request_logs(site_id, id DESC)")
        db.execSQL("CREATE INDEX http_request_logs_site_time_idx ON http_request_logs(site_id, created_at DESC)")
    }

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_STOPPED = "STOPPED"
        const val RUNTIME_STATIC = "STATIC"
        const val RUNTIME_PYTHON = "PYTHON"
        const val DEPLOYMENT_APPLIED = "APPLIED"
        const val DEPLOYMENT_ROLLED_BACK = "ROLLED_BACK"
        private const val DATABASE_NAME = "anb-local-hosting.db"
        const val MAX_REQUEST_LOGS_PER_SITE = 2_000
        const val REQUEST_LOG_PAGE_SIZE = 100
        private const val DATABASE_VERSION = 6
        private const val MAX_REQUEST_LOG_PATH_CHARS = 2_048
        private const val MAX_REQUEST_LOG_ERROR_CHARS = 300
        private const val REQUEST_LOG_SUMMARY_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val PREFERENCES_NAME = "local-hosting-preferences"
        private const val PREF_LAN_SHARING = "lan-sharing-enabled"
        private const val PREF_KEEP_CPU_AWAKE = "keep-cpu-awake-while-hosting"
        private val LOCAL_HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,58}[a-z0-9])?")
        private val DEPLOYMENT_COLUMNS = arrayOf(
            "id", "site_id", "repository", "ref", "commit_sha", "backup_file", "status", "created_at",
        )
        private val REQUEST_LOG_COLUMNS = arrayOf(
            "id", "site_id", "method", "path", "status_code", "response_bytes", "error_message", "created_at",
        )
    }
}
