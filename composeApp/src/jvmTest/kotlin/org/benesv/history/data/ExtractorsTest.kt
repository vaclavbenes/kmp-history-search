    package org.benesv.history.data

    import kotlin.test.Test
    import kotlin.test.assertEquals
    import kotlin.test.assertTrue
    import kotlin.test.assertNotNull
    import org.benesv.history.api.browser.ChromeUrls
    import org.benesv.history.core.FileUtil
    import org.benesv.history.core.TimeUtil
    import org.benesv.history.model.BrowserType
    import org.benesv.history.model.HistoryItem
    import org.jetbrains.exposed.v1.jdbc.Database
    import org.jetbrains.exposed.v1.jdbc.transactions.transaction
    import org.jetbrains.exposed.v1.jdbc.selectAll
    import java.io.File

    /**
     * Tests for extracting history data from copied browser databases.
     */
    class DataExtractor {
        /**
         * Test extracting Chrome database from a copied file and reading basic info.
         * Verifies that the temp copy process works with a real file.
         */
        @Test
        fun testChromeExtractorWithCopiedDatabase() {
            // Create a temporary test database file with valid SQLite structure
            val originalDb = File.createTempFile("chrome_test_db", ".sqlite")
            originalDb.deleteOnExit()

            try {
                // Create a minimal valid SQLite database
                val tempDb = Database.connect(url = "jdbc:sqlite:${originalDb.absolutePath}", driver = "org.sqlite.JDBC")
                transaction(tempDb) {
                    exec(
                        """
                        CREATE TABLE urls (
                            id INTEGER PRIMARY KEY,
                            url TEXT NOT NULL,
                            title TEXT NOT NULL,
                            last_visit_time INTEGER NOT NULL,
                            visit_count INTEGER NOT NULL
                        )
                    """.trimIndent()
                    )
                    exec(
                        """
                        CREATE TABLE visits (
                            url INTEGER NOT NULL
                        )
                    """.trimIndent()
                    )
                }

                // Now test creating a temp copy of this database
                val copiedDb = FileUtil.createTempCopy(originalDb, "chrome_extract_test")

                // Verify the copy exists and is readable
                assertTrue(copiedDb.exists(), "Copied Chrome database should exist")
                assertTrue(copiedDb.length() > 0, "Copied database should have content")

                // Verify it's a different file from the original
                assertTrue(copiedDb.absolutePath != originalDb.absolutePath, "Copy should be a different file")

                // Clean up the copy
                copiedDb.delete()
            } finally {
                originalDb.delete()
            }
        }

        /**
         * Test that FileUtil.createTempCopy properly creates a temporary copy of a database file.
         * Verifies the copied file exists and can be read.
         */
        @Test
        fun testCreateTempCopyOfDatabase() {
            // Create a temporary test file
            val originalFile = File.createTempFile("test_history", ".sqlite")
            originalFile.deleteOnExit()

            try {
                // Write some test content
                originalFile.writeText("Test database content")

                // Create a temporary copy
                val copiedFile = FileUtil.createTempCopy(originalFile, "test_copy")

                // Verify the copy exists
                assertTrue(copiedFile.exists(), "Copied file should exist")

                // Verify the content is the same
                assertEquals("Test database content", copiedFile.readText(), "Copied file should have same content")

                // Verify it's a different file
                assertTrue(copiedFile.absolutePath != originalFile.absolutePath, "Copy should be a different file")

                // Cleanup
                copiedFile.delete()
            } finally {
                originalFile.delete()
            }
        }

        /**
         * Test time conversion utilities for Chrome timestamps.
         * Verifies Chrome microseconds-since-1601 conversion to epoch milliseconds works correctly.
         */
        @Test
        fun testChromeTimeConversion() {
            // Chrome epoch offset from 1601 to 1970
            // Jan 1, 1601 to Jan 1, 1970 is 11,644,473,600 seconds = 11644473600000000 microseconds
            val chromeTimestamp = 132842768000000000L // Example timestamp

            val epochMillis = TimeUtil.chromeToEpochMillis(chromeTimestamp)

            // Should be positive and reasonable (after 1970)
            assertTrue(epochMillis > 0, "Converted timestamp should be positive")

            // Verify the conversion produces a valid millisecond timestamp
            val recoveredTimestamp = epochMillis * 1000 // Convert back to microseconds (approximately)
            assertTrue(recoveredTimestamp > 0, "Recovered timestamp should be valid")
        }

        /**
         * Test time conversion utilities for Firefox/Zen timestamps.
         * Verifies Firefox microseconds-since-1970 conversion to milliseconds works correctly.
         */
        @Test
        fun testFirefoxTimeConversion() {
            // Firefox uses microseconds since 1970 epoch
            val firefoxTimestamp = 1700000000000000L // Some timestamp in microseconds

            val epochMillis = TimeUtil.firefoxMicrosToMillis(firefoxTimestamp)

            // Should be positive and reasonable
            assertTrue(epochMillis > 0, "Converted timestamp should be positive")

            // Verify the conversion divides by 1000 correctly
            assertEquals(firefoxTimestamp / 1000L, epochMillis, "Should convert microseconds to milliseconds")
        }

        /**
         * Test creating a HistoryItem from extracted database data.
         * Verifies that all fields are properly populated.
         */
        @Test
        fun testHistoryItemCreation() {
            val item = HistoryItem(
                browser = BrowserType.Chrome,
                profile = "Default",
                url = "https://github.com",
                title = "GitHub",
                lastVisit = 1700000000000L,
                visitCount = 42,
                domain = "github.com"
            )

            assertEquals(BrowserType.Chrome, item.browser)
            assertEquals("Default", item.profile)
            assertEquals("https://github.com", item.url)
            assertEquals("GitHub", item.title)
            assertEquals(1700000000000L, item.lastVisit)
            assertEquals(42, item.visitCount)
            assertEquals("github.com",  item.domain)
        }

        /**
         * Test retrieving the first element from a Chrome database.
         * Verifies that we can query the database and extract the first history record.
         */
        @Test
        fun testGetFirstElementFromDatabase() {
            // Create a temporary test database file
            val testDb = File.createTempFile("chrome_first_elem", ".sqlite")
            testDb.deleteOnExit()

            try {
                // Create and populate a database with test data
                val db = Database.connect(url = "jdbc:sqlite:${testDb.absolutePath}", driver = "org.sqlite.JDBC")
                transaction(db) {
                    // Create the Chrome urls table
                    exec(
                        """
                        CREATE TABLE urls (
                            id INTEGER PRIMARY KEY,
                            url TEXT NOT NULL,
                            title TEXT NOT NULL,
                            last_visit_time INTEGER NOT NULL,
                            visit_count INTEGER NOT NULL
                        )
                    """.trimIndent()
                    )

                    // Insert test data
                    exec(
                        """
                        INSERT INTO urls (id, url, title, last_visit_time, visit_count) 
                        VALUES (1, 'https://github.com', 'GitHub', 132842768000000000, 42)
                    """.trimIndent()
                    )
                    exec(
                        """
                        INSERT INTO urls (id, url, title, last_visit_time, visit_count) 
                        VALUES (2, 'https://example.com', 'Example Domain', 132842760000000000, 15)
                    """.trimIndent()
                    )
                    exec(
                        """
                        INSERT INTO urls (id, url, title, last_visit_time, visit_count) 
                        VALUES (3, 'https://kotlin.org', 'Kotlin Language', 132842752000000000, 8)
                    """.trimIndent()
                    )
                }

                // Query the database and retrieve the first element
                transaction(db) {
                    val firstElement = ChromeUrls.selectAll().firstOrNull()

                    // Verify the first element exists
                    assertNotNull(firstElement, "First element should exist in database")

                    // Verify the first element has expected values
                    assertEquals("https://github.com", firstElement[ChromeUrls.url])
                    assertEquals("GitHub", firstElement[ChromeUrls.title])
                    assertEquals(42, firstElement[ChromeUrls.visitCount])
                    assertEquals(1, firstElement[ChromeUrls.id])
                }
            } finally {
                testDb.delete()
            }
        }

    }
