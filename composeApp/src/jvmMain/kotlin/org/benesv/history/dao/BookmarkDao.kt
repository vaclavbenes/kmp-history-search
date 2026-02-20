package org.benesv.history.dao

import org.benesv.history.api.app.BookmarkEntity
import org.benesv.history.api.app.Bookmarks
import org.benesv.history.api.app.FaviconEntity
import org.benesv.history.db.DbExecutor
import org.benesv.history.model.Bookmark
import org.benesv.history.model.BrowserType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Data Access Object for Bookmark operations.
 * Encapsulates all database queries related to bookmarks.
 */
class BookmarkDao(private val db: DbExecutor) {

    /**
     * Retrieve all bookmarks sorted by date added (descending).
     */
    suspend fun all(): List<Bookmark> = db.query {
        BookmarkEntity.all()
            .orderBy(Bookmarks.dateAdded to SortOrder.DESC)
            .map { it.toModel() }
    }

    /**
     * Retrieve bookmarks by browser type.
     */
    suspend fun findByBrowser(browserType: BrowserType): List<Bookmark> = db.query {
        BookmarkEntity.find { Bookmarks.browser eq browserType.name }
            .orderBy(Bookmarks.dateAdded to SortOrder.DESC)
            .map { it.toModel() }
    }

    /**
     * Retrieve bookmarks by folder path.
     */
    suspend fun findByFolder(folder: String): List<Bookmark> = db.query {
        BookmarkEntity.find { Bookmarks.folder eq folder }
            .orderBy(Bookmarks.dateAdded to SortOrder.DESC)
            .map { it.toModel() }
    }

    /**
     * Retrieve a page of bookmarks sorted by date added (descending).
     */
    suspend fun page(limit: Int, offset: Int): List<Bookmark> = db.query {
        BookmarkEntity.all()
            .orderBy(Bookmarks.dateAdded to SortOrder.DESC)
            .limit(limit)
            .drop(offset)
            .map { it.toModel() }
    }

    /**
     * Delete all bookmarks for a specific browser.
     */
    suspend fun deleteByBrowser(browserType: BrowserType) = db.query {
        Bookmarks.deleteWhere { Bookmarks.browser eq browserType.name }
    }

    /**
     * Check if any bookmark records exist.
     */
    suspend fun hasRecords(): Boolean = db.query {
        BookmarkEntity.all().limit(1).empty().not()
    }

    /**
     * Count total number of bookmark records.
     */
    suspend fun count(): Long = db.query {
        BookmarkEntity.all().count()
    }

    /**
     * Insert or update a bookmark.
     * Returns the entity after save.
     */
    suspend fun upsert(bookmark: Bookmark, faviconEntity: FaviconEntity? = null): BookmarkEntity = db.query {
        val existing = BookmarkEntity.find { 
            (Bookmarks.url eq bookmark.url) and 
            (Bookmarks.browser eq bookmark.browser.name) and 
            (Bookmarks.profile eq bookmark.profile) 
        }.firstOrNull()

        val entity = if (existing != null) {
            existing.apply {
                title = bookmark.title
                folder = bookmark.folder
                dateAdded = bookmark.dateAdded
                domain = bookmark.domain
            }
        } else {
            BookmarkEntity.new {
                browser = bookmark.browser.name
                profile = bookmark.profile
                url = bookmark.url
                title = bookmark.title
                folder = bookmark.folder
                dateAdded = bookmark.dateAdded
                domain = bookmark.domain
            }
        }

        // Set favicon if provided
        if (faviconEntity != null) {
            entity.favicon = faviconEntity
        }

        entity
    }

    /**
     * Find a bookmark entity by its ID.
     */
    suspend fun findById(id: Int): BookmarkEntity? = db.query {
        BookmarkEntity.findById(id)
    }

    /**
     * Delete a bookmark by its ID.
     */
    suspend fun deleteById(id: Int) = db.query {
        Bookmarks.deleteWhere { Bookmarks.id eq id }
    }
}
