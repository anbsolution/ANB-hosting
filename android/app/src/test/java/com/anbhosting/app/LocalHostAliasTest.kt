package com.anbhosting.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalHostAliasTest {
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
    fun createsCaseNormalizedAliasAndFindsItsWebsite() {
        val site = store.createSite("Alias target")
        val alias = store.createLocalHostAlias(site.id, "Shop-Front")

        assertEquals("shop-front.localhost", alias.hostname)
        assertEquals(site.id, store.findSiteByLocalHostname(alias.hostname)?.id)
        assertEquals(listOf(alias), store.listLocalHostAliases(site.id))
        assertEquals(site.id, store.findSiteByLocalHostname("SHOP-FRONT.LOCALHOST.")?.id)
    }

    @Test
    fun localAliasCannotClaimAnotherSitesDefaultHostname() {
        val first = store.createSite("First site")
        val second = store.createSite("Second site")

        val failure = assertThrows(IllegalStateException::class.java) {
            store.createLocalHostAlias(first.id, second.slug)
        }

        assertTrue(failure.message.orEmpty().contains("belongs to another website"))
        assertEquals(second.id, store.findSiteByLocalHostname(second.localHostname)?.id)
    }

    @Test
    fun onlySingleLocalLabelsAreAcceptedAndAliasesMustBeUnique() {
        val first = store.createSite("First site")
        val second = store.createSite("Second site")

        assertThrows(IllegalArgumentException::class.java) {
            store.createLocalHostAlias(first.id, "shop.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.createLocalHostAlias(first.id, "localhost")
        }
        store.createLocalHostAlias(first.id, "shop")
        assertThrows(IllegalArgumentException::class.java) {
            store.createLocalHostAlias(second.id, "SHOP")
        }
    }

    @Test
    fun deletingAliasOrSiteRemovesHostnameRouting() {
        val site = store.createSite("Alias target")
        val alias = store.createLocalHostAlias(site.id, "shop")

        store.deleteLocalHostAlias(site.id, alias.id)
        assertNull(store.findSiteByLocalHostname(alias.hostname))

        val secondAlias = store.createLocalHostAlias(site.id, "store")
        store.deleteSite(site.id)
        assertNull(store.findSiteByLocalHostname(secondAlias.hostname))
    }

    @Test
    fun upgradesVersionThreeCatalogWithoutLosingExistingSites() {
        store.close()
        context.deleteDatabase("anb-local-hosting.db")
        context.getDatabasePath("anb-local-hosting.db").parentFile?.mkdirs()
        val legacyDatabase = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("anb-local-hosting.db"),
            null,
        )
        legacyDatabase.execSQL(
            """CREATE TABLE sites (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                slug TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        legacyDatabase.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site_id TEXT,
                action TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        legacyDatabase.execSQL(
            "CREATE TABLE daily_traffic (site_id TEXT, day TEXT, bytes INTEGER, PRIMARY KEY(site_id, day))",
        )
        legacyDatabase.execSQL(
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
        legacyDatabase.insertOrThrow(
            "sites",
            null,
            ContentValues().apply {
                put("id", "legacy-site-id")
                put("name", "Existing site")
                put("slug", "existing")
                put("status", LocalHostingStore.STATUS_ACTIVE)
                put("created_at", 1L)
                put("updated_at", 1L)
            },
        )
        legacyDatabase.version = 3
        legacyDatabase.close()

        store = LocalHostingStore(context)
        val existing = store.findSiteBySlug("existing")
        assertEquals("legacy-site-id", existing?.id)
        assertEquals(LocalHostingStore.STATUS_ACTIVE, existing?.status)

        val alias = store.createLocalHostAlias("legacy-site-id", "migrated")
        assertEquals("legacy-site-id", store.findSiteByLocalHostname(alias.hostname)?.id)
    }
}
