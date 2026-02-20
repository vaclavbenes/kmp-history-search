package org.benesv.history.data

/**
 * Configuration constants for the history repository and related components.
 * 
 * This object centralizes all configuration values used by the repository,
 * making them easy to find, modify, and document.
 */
object RepositoryConfig {
    
    /**
     * Name of the SQLite database file used for caching history data.
     */
    const val CACHE_DB_FILE = "history.sqlite"
    
    /**
     * Number of history items to load per page for lazy loading.
     * Larger values load more data at once but may impact UI responsiveness.
     */
    const val PAGE_SIZE = 1000
    
    /**
     * SQLite-specific configuration values.
     */
    object Sqlite {
        /**
         * Timeout in milliseconds for SQLite busy state.
         * When the database is locked, SQLite will wait this long before returning an error.
         */
        const val BUSY_TIMEOUT_MS = 5_000
    }
    
    /**
     * Favicon fetching and caching configuration.
     */
    object Favicon {
        /**
         * Connection timeout in milliseconds when fetching favicons from remote servers.
         */
        const val CONNECT_TIMEOUT_MS = 5_000
        
        /**
         * Read timeout in milliseconds when downloading favicon data.
         */
        const val READ_TIMEOUT_MS = 5_000
        
        /**
         * Number of favicons to fetch in parallel within each batch.
         * Smaller values reduce server load, larger values speed up fetching.
         */
        const val BATCH_SIZE = 5
        
        /**
         * Delay in milliseconds between individual favicon requests to avoid overwhelming servers.
         */
        const val DELAY_BETWEEN_REQUESTS_MS = 100L
        
        /**
         * Base delay in milliseconds for exponential backoff when retrying failed favicon downloads.
         * Actual delay = RETRY_BASE_DELAY_MS * attempt_number
         */
        const val RETRY_BASE_DELAY_MS = 500L
    }
}
