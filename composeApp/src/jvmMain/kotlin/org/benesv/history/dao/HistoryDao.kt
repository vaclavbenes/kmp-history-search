package org.benesv.history.dao

import org.benesv.history.api.app.FaviconEntity
import org.benesv.history.api.app.History
import org.benesv.history.api.app.HistoryEntity
import org.benesv.history.db.DbExecutor
import org.benesv.history.model.BrowserType
import org.benesv.history.model.HistoryItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Data Access Object for History operations.
 * Encapsulates all database queries related to history items.
 */
class HistoryDao(private val db: DbExecutor) {

    /**
     * Retrieve a page of history items sorted by last visit (descending).
     */
    suspend fun page(limit: Int, offset: Int): List<HistoryItem> = db.query {
        HistoryEntity.all()
            .orderBy(History.lastVisit to SortOrder.DESC)
            .limit(limit)
            .drop(offset)
            .map { it.toModel() }
    }

    /**
     * Retrieve all history items sorted by last visit (descending).
     */
    suspend fun all(): List<HistoryItem> = db.query {
        HistoryEntity.all()
            .orderBy(History.lastVisit to SortOrder.DESC)
            .map { it.toModel() }
    }

    /**
     * Delete all history items with lastVisit >= epochMs.
     */
    suspend fun deleteSince(epochMs: Long) = db.query {
        History.deleteWhere { History.lastVisit greaterEq epochMs }
    }

    /**
     * Check if any history records exist.
     */
    suspend fun hasRecords(): Boolean = db.query {
        HistoryEntity.all().limit(1).empty().not()
    }

    /**
     * Count total number of history records.
     */
    suspend fun count(): Long = db.query {
        HistoryEntity.all().count()
    }

    /**
     * Upsert a history item (update if exists by URL, insert otherwise).
     * Returns the entity after save.
     */
    suspend fun upsert(item: HistoryItem, faviconEntity: FaviconEntity? = null): HistoryEntity = db.query {
        val existing = HistoryEntity.find { History.url eq item.url }.firstOrNull()

        val entity = if (existing != null) {
            existing.apply {
                browser = item.browser.name
                profile = item.profile
                title = item.title
                lastVisit = item.lastVisit
                visitCount = item.visitCount
                domain = item.domain
            }
        } else {
            HistoryEntity.new {
                browser = item.browser.name
                profile = item.profile
                url = item.url
                title = item.title
                lastVisit = item.lastVisit
                visitCount = item.visitCount
                domain = item.domain
            }
        }

        // Set favicon if provided
        if (faviconEntity != null) {
            entity.favicon = faviconEntity
        }

        entity
    }

    /**
     * Find a history entity by its ID.
     */
    suspend fun findById(id: Int): HistoryEntity? = db.query {
        HistoryEntity.findById(id)
    }
}

/**
 * Extension function to convert HistoryEntity to HistoryItem model.
 */
private fun HistoryEntity.toModel() = HistoryItem(
    browser = BrowserType.valueOf(browser),
    profile = profile,
    url = url,
    title = title,
    lastVisit = lastVisit,
    visitCount = visitCount,
    domain = domain,
    favicon = favicon?.toModel(),
)
