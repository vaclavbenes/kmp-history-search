package org.benesv.history.data

import org.benesv.history.api.browser.ChromeUrls
import org.benesv.history.api.browser.MozPlaces
import org.benesv.history.core.FileUtil
import org.benesv.history.core.PathsMac
import org.benesv.history.core.TimeUtil
import org.benesv.history.core.domainOf
import org.benesv.history.core.isInternalUrl
import org.benesv.history.core.Log
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

internal interface HistoryExtractor {
    fun isInstalled(): Boolean
    suspend fun extract(historyRepository: HistoryRepository, limit: Int = 1000): List<HistoryItem>
}

private fun exposedConnectReadOnly(dbFile: File, label: String? = null): Database {
    val lbl = label ?: ""
    val spacer = if (lbl.isNotEmpty()) "" else ""
    // Example: [DB][RO][Chrome:Default] /tmp/chrome_hist_xxx.sqlite
    Log.i("[DB][RO]${lbl} ${dbFile.absolutePath}")
    val url = "jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro"
    return Database.connect(url = url, driver = "org.sqlite.JDBC")
}

class ChromeExtractor : HistoryExtractor {
    override fun isInstalled(): Boolean = PathsMac.chromeRoot.exists()

    private fun profiles(): List<File> = PathsMac.chromeRoot.listFiles { f ->
        f.isDirectory && (f.name == "Default" || f.name.startsWith("Profile "))
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    override suspend fun extract(historyRepository: HistoryRepository, limit: Int): List<HistoryItem> {
        val out = mutableListOf<HistoryItem>()
        // Calculate timestamp for the last 24 hours in Chrome format (microseconds since 1601)
        val oneDayAgoMillis = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val oneDayAgoChromeMicros = (oneDayAgoMillis + TimeUtil.CHROME_EPOCH_OFFSET_MILLIS) * 1000L

        for (p in profiles()) {
            val db = File(p, "History")
            if (!db.exists()) continue
            val copy = FileUtil.createTempCopy(db, "chrome_hist_")
            try {
                val dbConn = exposedConnectReadOnly(copy, "[Chrome:${p.name}]")
                transaction(dbConn) {
                    val results = ChromeUrls
                        .selectAll()
                        .where { ChromeUrls.lastVisitTime greaterEq oneDayAgoChromeMicros }
                        .orderBy(ChromeUrls.lastVisitTime, SortOrder.DESC)
                        .limit(limit)
                        .toList()

                    for (row in results) {
                        val url = row[ChromeUrls.url]
                        if (isInternalUrl(url)) continue

                        val title = row[ChromeUrls.title]
                        val last = TimeUtil.chromeToEpochMillis(row[ChromeUrls.lastVisitTime])
                        val count = row[ChromeUrls.visitCount]
                        val domain = domainOf(url)

                        out += HistoryItem(
                            browser = BrowserType.Chrome,
                            profile = p.name,
                            url = url,
                            title = title,
                            lastVisit = last,
                            visitCount = count,
                            domain = domain,
                            favicon = null, // Lazy load
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                copy.delete()
            }
        }
        return out
    }
}

class ZenExtractor : HistoryExtractor {
    private fun zenProfilesDir(): File? = PathsMac.zenRootCandidates.firstOrNull { it.exists() }

    override fun isInstalled(): Boolean = zenProfilesDir() != null

    private fun profiles(): List<File> = zenProfilesDir()?.let { root ->
        val profilesRoot = File(root, "Profiles")
        val candidates = if (profilesRoot.exists()) profilesRoot else root
        candidates.walkTopDown().maxDepth(2)
            .filter { it.isDirectory && (it.name.contains("Default Profile") || it.name.contains("Default (release)") || it.name.contains("default")) }
            .toList()
    } ?: emptyList()

    override suspend fun extract(historyRepository: HistoryRepository, limit: Int): List<HistoryItem> {
        val out = mutableListOf<HistoryItem>()
        val oneDayAgoMillis = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val oneDayAgoFirefoxMicros = oneDayAgoMillis * 1000L

        for (p in profiles()) {
            val db = File(p, "places.sqlite")
            if (!db.exists()) continue
            val copy = FileUtil.createTempCopy(db, "zen_places_")

            try {
                val dbConn = exposedConnectReadOnly(copy, "[Zen:${p.name}]")
                transaction(dbConn) {
                    val results = MozPlaces
                        .selectAll()
                        .where { MozPlaces.lastVisitDate greaterEq oneDayAgoFirefoxMicros }
                        .orderBy(MozPlaces.lastVisitDate, SortOrder.DESC)
                        .limit(limit)
                        .toList()

                    for (row in results) {
                        val url = row[MozPlaces.url]
                        if (isInternalUrl(url)) continue

                        val title = row[MozPlaces.title]
                        val last = TimeUtil.firefoxMicrosToMillis(row[MozPlaces.lastVisitDate])
                        val count = row[MozPlaces.visitCount]
                        val domain = domainOf(url)

                        out += HistoryItem(
                            browser = BrowserType.Zen,
                            profile = p.name,
                            url = url,
                            title = title ?: "",
                            lastVisit = last,
                            visitCount = count,
                            domain = domain,
                            favicon = null, // Lazy load (fetch in background)
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                copy.delete()
            }
        }
        return out
    }
}

class ThoriumExtractor : HistoryExtractor {
    override fun isInstalled(): Boolean = PathsMac.thoriumRoot.exists()

    private fun profiles(): List<File> = PathsMac.thoriumRoot.listFiles { f ->
        f.isDirectory && (f.name == "Default" || f.name.startsWith("Profile "))
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    override suspend fun extract(historyRepository: HistoryRepository, limit: Int): List<HistoryItem> {
        val out = mutableListOf<HistoryItem>()
        // Calculate timestamp for the last 24 hours in Chrome format (microseconds since 1601)
        val oneDayAgoMillis = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val oneDayAgoChromeMicros = (oneDayAgoMillis + TimeUtil.CHROME_EPOCH_OFFSET_MILLIS) * 1000L

        for (p in profiles()) {
            val db = File(p, "History")
            if (!db.exists()) continue
            val copy = FileUtil.createTempCopy(db, "thorium_hist_")
            try {
                val dbConn = exposedConnectReadOnly(copy, "[Thorium:${p.name}]")
                transaction(dbConn) {
                    val results = ChromeUrls
                        .selectAll()
                        .where { ChromeUrls.lastVisitTime greaterEq oneDayAgoChromeMicros }
                        .orderBy(ChromeUrls.lastVisitTime, SortOrder.DESC)
                        .limit(limit)
                        .toList()

                    for (row in results) {
                        val url = row[ChromeUrls.url]
                        if (isInternalUrl(url)) continue

                        val title = row[ChromeUrls.title]
                        val last = TimeUtil.chromeToEpochMillis(row[ChromeUrls.lastVisitTime])
                        val count = row[ChromeUrls.visitCount]
                        val domain = domainOf(url)

                        out += HistoryItem(
                            browser = BrowserType.Thorium,
                            profile = p.name,
                            url = url,
                            title = title,
                            lastVisit = last,
                            visitCount = count,
                            domain = domain,
                            favicon = null, // Lazy load
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                copy.delete()
            }
        }
        return out
    }
}
