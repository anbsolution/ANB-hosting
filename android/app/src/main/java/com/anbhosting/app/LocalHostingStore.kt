package com.anbhosting.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

data class LocalSite(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val createdAt: Long,
    val root: File,
)

data class LocalHostingEvent(
    val action: String,
    val detail: String,
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema upgrades are versioned here; existing site files are never dropped.
    }

    fun listSites(): List<LocalSite> {
        val result = mutableListOf<LocalSite>()
        readableDatabase.query(
            "sites",
            arrayOf("id", "name", "slug", "status", "created_at"),
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
        arrayOf("id", "name", "slug", "status", "created_at"),
        "slug = ?",
        arrayOf(slug),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toLocalSite() else null }

    fun createSite(rawName: String): LocalSite {
        val name = rawName.trim()
        require(name.length in 2..80) { "Website name must be 2 to 80 characters." }
        check(sitesDirectory.exists() || sitesDirectory.mkdirs()) { "Website storage could not be created." }

        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "site" }
            .take(36)
            .trimEnd('-')
        val id = UUID.randomUUID().toString()
        val slug = "$base-${id.take(8)}"
        val root = File(sitesDirectory, slug)
        check(root.mkdirs()) { "Website folder could not be created." }

        try {
            val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
            File(root, "index.html").writeText(
                """<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>$safeName</title></head>
<body><main><h1>$safeName</h1><p>This starter page is ready for local preview.</p></main></body>
</html>
""".trimIndent(),
                Charsets.UTF_8,
            )
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("id", id)
                put("name", name)
                put("slug", slug)
                put("status", STATUS_STOPPED)
                put("created_at", now)
                put("updated_at", now)
            }
            check(writableDatabase.insertOrThrow("sites", null, values) != -1L) {
                "Website could not be saved."
            }
            recordEvent(id, "website.created", name)
            return LocalSite(id, name, slug, STATUS_STOPPED, now, root)
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
        writableDatabase.delete("sites", "id = ?", arrayOf(id))
        recordEvent(id, "website.deleted", slug)
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

    fun isLanSharingEnabled(): Boolean = preferences.getBoolean(PREF_LAN_SHARING, false)

    fun setLanSharingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_LAN_SHARING, enabled).apply()
        recordEvent(null, "server.lan_sharing_changed", if (enabled) "enabled" else "disabled")
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
            createdAt = getLong(4),
            root = siteRoot(slug),
        )
    }

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_STOPPED = "STOPPED"
        private const val DATABASE_NAME = "anb-local-hosting.db"
        private const val DATABASE_VERSION = 1
        private const val PREFERENCES_NAME = "local-hosting-preferences"
        private const val PREF_LAN_SHARING = "lan-sharing-enabled"
    }
}
