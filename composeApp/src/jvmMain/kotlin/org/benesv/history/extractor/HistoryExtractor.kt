package org.benesv.history.extractor

import org.benesv.history.data.HistoryRepository
import org.benesv.history.model.HistoryItem


internal interface HistoryExtractor {
    fun isInstalled(): Boolean
    suspend fun extract(historyRepository: HistoryRepository, limit: Int = 1000): List<HistoryItem>

}
