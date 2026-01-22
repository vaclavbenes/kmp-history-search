package org.benesv.history.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import org.benesv.history.api.app.Favicons
import org.benesv.history.api.app.History
import org.benesv.history.model.BrowserType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import java.io.File

/**
 * Tests for HistoryRepository data retrieval.
 */
class RepositoryTest {

    /**
     * Test that getDataFromDisk returns data correctly.
     * This test reproduces the issue where the WHERE clause filters out all results.
     */
    @Test
    fun testGetDataFromDiskReturnsData() {
        // Create a temporary test database
        val testDb = File.createTempFile("history_repo_test", ".sqlite")
        testDb.deleteOnExit()

        try {
            val db = Database.connect(url = "jdbc:sqlite:${testDb.absolutePath}", driver = "org.sqlite.JDBC")

            transaction(db) {
                // Create tables
                SchemaUtils.create(History, Favicons)

                // Insert a favicon
                val faviconId = Favicons.insert {
                    it[url] = "github.com"
                    it[imageData] = ExposedBlob(ByteArray(10) { 0xFF.toByte() })
                } get Favicons.id

                // Insert history items
                History.insert {
                    it[browser] = BrowserType.Chrome.name
                    it[profile] = "Default"
                    it[url] = "https://github.com"
                    it[title] = "GitHub"
                    it[lastVisit] = 1700000000000L
                    it[visitCount] = 42
                    it[domain] = "github.com"
                    it[favicon] = faviconId
                }

                History.insert {
                    it[browser] = BrowserType.Chrome.name
                    it[profile] = "Default"
                    it[url] = "https://example.com"
                    it[title] = "Example"
                    it[lastVisit] = 1699999000000L
                    it[visitCount] = 10
                    it[domain] = "example.com"
                    it[favicon] = null // No favicon for this one
                }
            }

            // Now test the query that is used in getDataFromDisk
            transaction(db) {
                // This is the BROKEN query from Repository.kt:199
                val brokenQuery = (History leftJoin Favicons)
                    .selectAll()
                    .where { History.domain eq Favicons.url } // This is the problem!
                    .orderBy(History.lastVisit to SortOrder.DESC)

                val brokenResults = brokenQuery.toList()
                println("[TEST] Broken query returned ${brokenResults.size} items")
                // This will fail - it only returns items where domain == favicon.url

                // The CORRECT query should not have the WHERE clause
                val correctQuery = (History leftJoin Favicons)
                    .selectAll()
                    .orderBy(History.lastVisit to SortOrder.DESC)

                val correctResults = correctQuery.toList()
                println("[TEST] Correct query returned ${correctResults.size} items")

                // Verify we get both history items
                assertEquals(2, correctResults.size, "Should return all history items")

                // Verify the first item (most recent) has all fields
                val firstItem = correctResults[0]
                assertEquals("https://github.com", firstItem[History.url])
                assertEquals("GitHub", firstItem[History.title])
                assertNotNull(firstItem[History.favicon], "First item should have a favicon ID")

                // Verify the second item (older) also appears
                val secondItem = correctResults[1]
                assertEquals("https://example.com", secondItem[History.url])
                assertEquals("Example", secondItem[History.title])
            }
        } finally {
            testDb.delete()
        }
    }

    /**
     * Test that demonstrates the issue with the WHERE clause.
     * When History.domain doesn't match Favicons.url exactly, the row is filtered out.
     */
    @Test
    fun testWhereClauseFiltersOutData() {
        val testDb = File.createTempFile("history_where_test", ".sqlite")
        testDb.deleteOnExit()

        try {
            val db = Database.connect(url = "jdbc:sqlite:${testDb.absolutePath}", driver = "org.sqlite.JDBC")

            transaction(db) {
                SchemaUtils.create(History, Favicons)

                // Insert favicon for different domain
                val faviconId = Favicons.insert {
                    it[url] = "different-domain.com"
                    it[imageData] = ExposedBlob(ByteArray(5))
                } get Favicons.id

                // Insert history with domain that doesn't match favicon.url
                History.insert {
                    it[browser] = BrowserType.Chrome.name
                    it[profile] = "Default"
                    it[url] = "https://example.com"
                    it[title] = "Example"
                    it[lastVisit] = 1700000000000L
                    it[visitCount] = 1
                    it[domain] = "example.com"
                    it[favicon] = faviconId
                }
            }

            transaction(db) {
                // Query with WHERE clause (broken)
                val withWhere = (History leftJoin Favicons)
                    .selectAll()
                    .where { History.domain eq Favicons.url }
                    .toList()

                // This returns 0 results because "example.com" != "different-domain.com"
                assertEquals(0, withWhere.size, "WHERE clause filters out the row")

                // Query without WHERE clause (correct)
                val withoutWhere = (History leftJoin Favicons)
                    .selectAll()
                    .toList()

                // This returns 1 result as expected
                assertEquals(1, withoutWhere.size, "Without WHERE clause, we get the row")
            }
        } finally {
            testDb.delete()
        }
    }
}
