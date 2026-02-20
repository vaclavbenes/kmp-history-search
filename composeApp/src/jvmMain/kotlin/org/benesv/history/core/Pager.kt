package org.benesv.history.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A reusable pagination component that encapsulates offset-based paging logic.
 *
 * This class handles:
 * - Tracking the current offset
 * - Detecting when there's no more data
 * - Thread-safe loading with mutex
 * - First page loading with reset
 *
 * @param T The type of items being paged
 * @param pageSize The number of items to load per page
 * @param loader The suspend function that loads a page of items given limit and offset
 */
class Pager<T>(
    private val pageSize: Int,
    private val loader: suspend (limit: Int, offset: Int) -> List<T>
) {
    private var offset = 0
    private var hasMore = true
    private val loading = Mutex()

    /**
     * Load the first page, resetting pagination state.
     * This should be called when you want to start from the beginning.
     *
     * @return The first page of items
     */
    suspend fun loadFirst(): List<T> = loading.withLock {
        offset = 0
        hasMore = true
        val page = loader(pageSize, 0)
        offset = page.size
        page
    }

    /**
     * Load the next page of items.
     * If there's no more data (previous page was empty), returns empty list.
     *
     * @return The next page of items, or empty list if no more data
     */
    suspend fun loadNext(): List<T> = loading.withLock {
        if (!hasMore) return emptyList()
        val page = loader(pageSize, offset)
        if (page.isEmpty()) {
            hasMore = false
        }
        offset += page.size
        page
    }

    /**
     * Check if there's potentially more data to load.
     * Note: This returns true until we've tried to load and got an empty page.
     */
    fun hasMore(): Boolean = hasMore

    /**
     * Reset the pager to initial state without loading data.
     * Useful if you want to manually reset pagination state.
     */
    fun reset() {
        offset = 0
        hasMore = true
    }
}
