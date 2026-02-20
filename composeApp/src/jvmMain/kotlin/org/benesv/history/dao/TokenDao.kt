package org.benesv.history.dao

import org.benesv.history.api.app.TokenEntity
import org.benesv.history.api.app.Tokens
import org.benesv.history.db.DbExecutor
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import java.util.Locale

/**
 * Data Access Object for Token operations.
 * Handles search suggestion tokens and their frequencies.
 */
class TokenDao(private val db: DbExecutor) {

    /**
     * Get token suggestions matching the given prefix.
     * Results are ordered by frequency (desc) and lastUsed (desc).
     */
    suspend fun suggestions(prefix: String, limit: Int = 5): List<String> = db.query {
        TokenEntity.Companion.find { Tokens.text like "$prefix%" }
            .orderBy(Tokens.frequency to SortOrder.DESC, Tokens.lastUsed to SortOrder.DESC)
            .limit(limit)
            .map { it.text }
    }

    /**
     * Save tokens from a search query.
     * Increments frequency for existing tokens or creates new ones.
     * @param query The search query to extract tokens from
     */
    suspend fun saveTokens(query: String) {
        val words = query
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length >= 3 }
            .distinct()

        if (words.isEmpty()) return

        val now = System.currentTimeMillis()

        db.query {
            words.forEach { word ->
                val existing = TokenEntity.Companion.find { Tokens.text eq word }.limit(1).firstOrNull()
                if (existing != null) {
                    existing.frequency = existing.frequency + 1
                    existing.lastUsed = now
                } else {
                    TokenEntity.Companion.new {
                        text = word
                        frequency = 1
                        lastUsed = now
                    }
                }
            }
        }
    }
}
