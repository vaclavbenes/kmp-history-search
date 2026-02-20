package org.benesv.history.dao

import org.benesv.history.api.app.FaviconEntity
import org.benesv.history.api.app.Favicons
import org.benesv.history.db.DbExecutor
import org.benesv.history.model.Favicon
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.deleteAll

/**
 * Data Access Object for Favicon operations.
 * Encapsulates all database queries related to favicons.
 */
class FaviconDao(private val db: DbExecutor) {

    /**
     * Find a favicon by its URL (domain).
     */
    suspend fun findByUrl(url: String): Favicon? = db.query {
        FaviconEntity.Companion.find { Favicons.url eq url }
            .firstOrNull()
            ?.toModel()
    }

    /**
     * Save or update a favicon for a given URL.
     * @param url The domain/URL for the favicon
     * @param imageData The favicon image data as bytes
     * @param overwrite If true, updates existing favicon; if false, keeps existing
     * @return The saved Favicon model
     */
    suspend fun save(url: String, imageData: ByteArray, overwrite: Boolean = false): Favicon = db.query {
        val existing = FaviconEntity.Companion.find { Favicons.url eq url }.firstOrNull()

        if (existing == null) {
            val newFavicon = FaviconEntity.Companion.new {
                this.url = url
                this.imageData = ExposedBlob(imageData)
            }
            newFavicon.toModel()
        } else {
            if (overwrite) {
                existing.imageData = ExposedBlob(imageData)
            }
            existing.toModel()
        }
    }

    /**
     * Find a favicon entity (not model) by URL.
     * Used when we need the entity to create relationships.
     */
    suspend fun findEntityByUrl(url: String): FaviconEntity? = db.query {
        FaviconEntity.Companion.find { Favicons.url eq url }.firstOrNull()
    }

    /**
     * Delete all favicons from the database.
     */
    suspend fun deleteAll() = db.query {
        Favicons.deleteAll()
    }

    /**
     * Count total number of favicon records.
     */
    suspend fun count(): Long = db.query {
        FaviconEntity.Companion.all().count()
    }
}
