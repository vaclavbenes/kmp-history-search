package org.benesv.history.extractor

import org.benesv.history.api.browser.ChromeUrls
import org.benesv.history.core.FileUtil
import org.benesv.history.core.PathsMac
import org.benesv.history.core.TimeUtil
import org.benesv.history.core.domainOf
import org.benesv.history.core.isInternalUrl
import org.benesv.history.data.HistoryRepository
import org.benesv.history.db.BrowserDbExecutor
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File

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
            val dbCopy = FileUtil.createTempCopy(db, "thorium_hist_")
            try {
                val db = BrowserDbExecutor(dbCopy,  "[Thorium:${p.name}]")
                    .connect()

                db.query {
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
                dbCopy.delete()
            }
        }
        return out
    }
}
