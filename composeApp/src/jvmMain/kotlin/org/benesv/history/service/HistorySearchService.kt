package org.benesv.history.service

import org.benesv.history.model.HistoryItem
import java.util.Locale

/**
 * Service responsible for searching and filtering history items.
 * Provides fuzzy search capabilities with relevance scoring.
 */
class HistorySearchService {

    /**
     * Filter history items using fuzzy search with multi-token matching and relevance scoring.
     * 
     * @param items The list of history items to filter
     * @param query The search query string
     * @return Filtered and sorted list of history items based on relevance
     */
    fun fuzzyFilter(items: List<HistoryItem>, query: String): List<HistoryItem> {
        val tokens = query
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

        if (tokens.isEmpty()) return items

        return items.mapNotNull { item ->
            val title = item.title.lowercase(Locale.ROOT)
            val url = item.url.lowercase(Locale.ROOT)
            val domain = item.domain.lowercase(Locale.ROOT)

            // All tokens must be present somewhere to qualify
            if (!tokens.all { t ->
                    title.contains(t) || url.contains(t) || domain.contains(t)
                }) {
                return@mapNotNull null
            }

            val score = calculateRelevanceScore(item, tokens, title, url, domain)
            item to score
        }
            .sortedWith(
                compareByDescending<Pair<HistoryItem, Int>> { it.second }
                    .thenByDescending { it.first.lastVisit }
                    .thenByDescending { it.first.visitCount }
            )
            .map { it.first }
    }

    /**
     * Calculate the relevance score for a history item based on search tokens.
     * 
     * Higher scores indicate better matches. Score is calculated based on:
     * - Token position in fields (domain > url > title)
     * - Token position within each field (start is better)
     * - Token order preservation in URL
     * - Visit count and recency (minor tiebreakers)
     * - Penalty for URLs with search parameters
     */
    private fun calculateRelevanceScore(
        item: HistoryItem,
        tokens: List<String>,
        title: String,
        url: String,
        domain: String
    ): Int {
        var total = 0

        // Score each token individually
        tokens.forEachIndexed { idx, token ->
            val importance = if (idx == 0) 100 else 35 // The first token is much more important

            // Prefer domain > url > title for each token
            val domainScore = fieldScore(domain, token, importance * 5)
            val urlScore = fieldScore(url, token, importance * 3)
            val titleScore = fieldScore(title, token, importance * 1)

            total += maxOf(domainScore, urlScore, titleScore)
        }

        // Bonus if tokens appear in URL in the same order they were typed
        if (tokens.size >= 2) {
            var lastIdx = -1
            var inOrder = true
            for (token in tokens) {
                val i = url.indexOf(token)
                if (i < 0 || i < lastIdx) {
                    inOrder = false
                    break
                }
                lastIdx = i
            }
            if (inOrder) total += 150
        }

        // Decrease importance for URLs with search parameters
        if (hasSearchParams(url)) {
            total = (total * 0.7).toInt()
        }

        // Slight recency and frequency tiebreakers
        total += (item.visitCount.coerceAtMost(50)) // up to +50
        total += ((item.lastVisit / (1000L * 60L * 60L * 24L)).toInt() % 7) // tiny, stable within a week

        return total
    }

    /**
     * Calculate a score for a field based on token matching.
     * 
     * @param field The field content (lowercase)
     * @param token The search token (lowercase)
     * @param base The base score multiplier
     * @return Score for this field, with bonuses for start position and early matches
     */
    private fun fieldScore(field: String, token: String, base: Int): Int {
        if (!field.contains(token)) return 0

        var score = base

        // Prefer terms at the start of the field
        if (field.startsWith(token)) {
            score += base / 2
        }

        // Small boost for closer position to the start
        val idx = field.indexOf(token)
        if (idx >= 0) {
            score += (base / 4).coerceAtLeast(1) * (10 - (idx / 10).coerceAtMost(10))
        }

        return score
    }

    /**
     * Check if a URL contains common search query parameters.
     * URLs with search parameters typically have less relevance as direct navigation targets.
     */
    private fun hasSearchParams(url: String): Boolean {
        return url.contains("?q=") ||
            url.contains("&q=") ||
            url.contains("?search=") ||
            url.contains("&search=") ||
            url.contains("?query=") ||
            url.contains("&query=")
    }
}
