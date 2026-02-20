package org.benesv.history.extractor

import org.benesv.history.api.browser.MozPlaces
import org.benesv.history.core.FileUtil
import org.benesv.history.core.PathsMac
import org.benesv.history.core.TimeUtil
import org.benesv.history.core.domainOf
import org.benesv.history.core.isInternalUrl
import org.benesv.history.data.HistoryRepository
import org.benesv.history.db.DbConnector
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File

class ZenExtractor : HistoryExtractor {
    override val browserType: BrowserType = BrowserType.Zen
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
            val dbCopy = FileUtil.createTempCopy(db, "zen_places_")

            try {

                val db = DbConnector(dbCopy, "[Zen:${p.name}]").db

                db.query {
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
                dbCopy.delete()
            }
        }
        return out
    }
}
