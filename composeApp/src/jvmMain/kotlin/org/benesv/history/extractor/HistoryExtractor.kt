package org.benesv.history.extractor

import org.benesv.history.data.HistoryRepository
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem


interface HistoryExtractor {
    val browserType: BrowserType
    fun isInstalled(): Boolean
    suspend fun extract(historyRepository: HistoryRepository, limit: Int = 1000): List<HistoryItem>

}
