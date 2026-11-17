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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDatabaseManagerTest {
    private lateinit var context: Context
    private lateinit var store: LocalHostingStore
    private lateinit var databases: LocalDatabaseManager
    private lateinit var site: LocalSite

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("anb-local-hosting.db")
        context.noBackupFilesDir.resolve("anb-local-databases").deleteRecursively()
        store = LocalHostingStore(context)
        site = store.createSite("Database tools")
        databases = LocalDatabaseManager(context, store)
    }

    @After
    fun tearDown() {
        runCatching {
            databases.deleteAllForSite(site.id)
            store.deleteSite(site.id)
        }
        store.close()
    }

    @Test
    fun createsQueriesExportsImportsAndDeletesPrivateDatabase() {
        databases.create(site.id, "catalog")
        assertEquals("catalog", databases.list(site.id).single().name)
        assertFalse(site.root.resolve("catalog.db").exists())

        databases.execute(site.id, "catalog", "CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        databases.execute(site.id, "catalog", "INSERT INTO products (name) VALUES ('ANB Hosting')")
        val result = databases.execute(site.id, "catalog", "SELECT id, name FROM products")

        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf("1", "ANB Hosting"), result.rows.single())

        val exported = ByteArrayOutputStream().also { databases.export(site.id, "catalog", it) }.toByteArray()
        databases.importDatabase(site.id, "restored", ByteArrayInputStream(exported))
        val restored = databases.execute(site.id, "restored", "SELECT name FROM products")
        assertEquals("ANB Hosting", restored.rows.single().single())

        databases.delete(site.id, "catalog")
        assertEquals(listOf("restored"), databases.list(site.id).map { it.name })
    }

    @Test
    fun rejectsUnsafeNamesMultipleStatementsAndNonSqliteImports() {
        assertThrows(IllegalArgumentException::class.java) { databases.create(site.id, "../outside") }
        databases.create(site.id, "safe")
        assertThrows(IllegalArgumentException::class.java) {
            databases.execute(site.id, "safe", "SELECT 1; DELETE FROM products")
        }
        assertThrows(IllegalArgumentException::class.java) {
            databases.execute(site.id, "safe", "ATTACH DATABASE '/tmp/other.db' AS other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            databases.importDatabase(site.id, "invalid", ByteArrayInputStream("not sqlite".toByteArray()))
        }
        assertTrue(databases.list(site.id).none { it.name == "invalid" })
    }

    @Test
    fun boundsQueryRowsAndColumns() {
        databases.create(site.id, "limits")
        databases.execute(site.id, "limits", "CREATE TABLE rows_to_read (value INTEGER)")
        val values = (1..101).joinToString(",") { "($it)" }
        databases.execute(site.id, "limits", "INSERT INTO rows_to_read (value) VALUES $values")

        val rows = databases.execute(site.id, "limits", "SELECT value FROM rows_to_read ORDER BY value")
        assertEquals(100, rows.rows.size)
        assertTrue(rows.truncated)

        val columns = (1..31).joinToString(",") { "$it AS column_$it" }
        val manyColumns = databases.execute(site.id, "limits", "SELECT $columns")
        assertEquals(30, manyColumns.columns.size)
        assertTrue(manyColumns.columnsTruncated)
    }
}
