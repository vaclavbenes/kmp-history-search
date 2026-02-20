package org.benesv.history.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.benesv.history.core.Log
import org.benesv.history.dao.FaviconDao
import org.benesv.history.data.RepositoryConfig
import org.benesv.history.extractor.FaviconExtractor
import org.benesv.history.model.Favicon
import org.benesv.history.model.HistoryItem
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages favicon fetching, caching, and persistence.
 * 
 * This manager is responsible for:
 * - Fetching favicons from remote servers
 * - Caching favicon data in the database
 * - Managing concurrent favicon requests to avoid duplicates
 * - Rate limiting and retry logic for network requests
 * - Batch processing of multiple favicon requests
 * 
 * @property faviconDao Data access object for favicon persistence
 */
class FaviconManager(
    private val faviconDao: FaviconDao,
) {
    /**
     * Mutex to synchronize access to the set of domains currently being fetched.
     */
    private val faviconsDomainsMutex = Mutex()
    
    /**
     * Set of domains that are currently being fetched to prevent duplicate requests.
     */
    private val faviconsDomains = mutableSetOf<String>()


    /**
     * Fetch missing favicons for a list of history items in the background.
     * 
     * This method processes unique domains from the provided items, fetches their favicons
     * in batches with rate limiting, and calls the onUpdate callback after each batch
     * to notify observers of changes.
     * 
     * @param items List of history items to fetch favicons for
     * @param onUpdate Callback invoked after each batch is processed to update observers
     */
    suspend fun fetchMissingFaviconsInBackground(
        items: List<HistoryItem>,
        onUpdate: suspend () -> Unit
    ) {
        Log.i("[Favicons] Starting background favicon fetch for ${items.size} items")

        val uniqueDomains = items.map { it.domain }.distinct()
        Log.i("[Favicons] Fetching favicons for ${uniqueDomains.size} unique domains")

        fetchFaviconsForDomains(uniqueDomains, onUpdate)
    }

    /**
     * Get or fetch a favicon for a domain.
     * 
     * This method first checks the database cache. If not found, it fetches the favicon
     * from remote servers, stores it in the database, and returns it.
     * 
     * The method ensures that only one fetch operation per domain is in progress at a time
     * to avoid duplicate requests.
     * 
     * @param domain The domain to get the favicon for
     * @param size The desired favicon size (default: 64px)
     * @return The favicon if found or fetched successfully, null otherwise
     */
    suspend fun getFaviconByDomain(domain: String, size: Int = 64): Favicon? = withContext(Dispatchers.IO) {
        // Check if already cached
        faviconDao.findByUrl(domain)?.let { return@withContext it }

        // Check if already being fetched by another coroutine
        if (!tryAcquireFetchLock(domain)) {
            return@withContext null
        }

        return@withContext try {
            fetchAndSaveFavicon(domain, size)
        } catch (e: Exception) {
            Log.w("[Favicons] Error fetching favicon for $domain: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            releaseFetchLock(domain)
        }
    }

    /**
     * Download favicon data from a URL with retry logic.
     * 
     * This method attempts to download favicon data from the specified URL with exponential
     * backoff retry logic. It validates the HTTP response code and content type before
     * accepting the data.
     * 
     * @param faviconUrl The URL to download the favicon from
     * @param maxRetries Maximum number of retry attempts (default: 2)
     * @return The favicon data as a byte array, or null if download fails
     */
    suspend fun downloadFaviconToByteArray(faviconUrl: String, maxRetries: Int = 2): ByteArray? {
        repeat(maxRetries + 1) { attempt ->
            try {
                return downloadFaviconAttempt(faviconUrl)
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    Log.w("Failed to download favicon from $faviconUrl after $maxRetries retries: ${e.message}")
                    return null
                }
                Log.i("Retry ${attempt + 1}/$maxRetries for $faviconUrl due to: ${e.message}")
                delay(RepositoryConfig.Favicon.RETRY_BASE_DELAY_MS * (attempt + 1))
            }
        }
        return null
    }

    /**
     * Fetch favicons for a list of domains with batching, rate limiting, and optional update callback.
     * 
     * @param domains List of domains to fetch favicons for
     * @param onBatchUpdate Optional callback invoked after each batch is processed
     */
    private suspend fun fetchFaviconsForDomains(
        domains: List<String>,
        onBatchUpdate: (suspend () -> Unit)? = null
    ) {
        val startTime = System.currentTimeMillis()
        val stats = FetchStats()

        domains.chunked(RepositoryConfig.Favicon.BATCH_SIZE).forEach { batch ->
            processBatch(batch, stats)
            onBatchUpdate?.invoke()
        }

        logFetchCompletion(stats, startTime)
    }

    /**
     * Process a single batch of domains.
     */
    private suspend fun processBatch(batch: List<String>, stats: FetchStats) {
        batch.forEach { domain ->
            processSingleDomain(domain, stats)
            delay(RepositoryConfig.Favicon.DELAY_BETWEEN_REQUESTS_MS)
        }
    }

    /**
     * Process a single domain fetch and update statistics.
     */
    private suspend fun processSingleDomain(domain: String, stats: FetchStats) {
        try {
            val favicon = getFaviconByDomain(domain)
            if (favicon?.imageData != null) {
                stats.incrementFetched()
                Log.i("[Favicons] ✓ Fetched favicon for $domain (ID: ${favicon.id})")
            } else {
                stats.incrementFailed()
            }
        } catch (e: Exception) {
            stats.incrementFailed()
            Log.w("[Favicons] ✗ Failed to fetch favicon for $domain: ${e.message}")
        }
    }

    /**
     * Try to acquire a lock for fetching a domain's favicon.
     * Returns true if lock was acquired, false if domain is already being fetched.
     */
    private suspend fun tryAcquireFetchLock(domain: String): Boolean {
        return faviconsDomainsMutex.withLock {
            faviconsDomains.add(domain)
        }
    }

    /**
     * Release the fetch lock for a domain.
     */
    private suspend fun releaseFetchLock(domain: String) {
        faviconsDomainsMutex.withLock {
            faviconsDomains.remove(domain)
        }
    }

    /**
     * Fetch favicon from remote servers and save it to the database.
     */
    private suspend fun fetchAndSaveFavicon(domain: String, size: Int): Favicon? {
        val candidates = FaviconExtractor.getCandidateFaviconUrls(domain, size)
        val bytes = fetchFirstNonEmpty(candidates) ?: return null
        return faviconDao.save(domain, bytes, overwrite = true)
    }

    /**
     * Try to fetch the first non-empty favicon from a list of candidate URLs.
     * Launches parallel requests and returns as soon as the first successful download completes.
     */
    private suspend fun fetchFirstNonEmpty(candidates: List<String>): ByteArray? = coroutineScope {
        val jobs = candidates.map { url ->
            async {
                runCatching { downloadFaviconToByteArray(url) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        val result = jobs.awaitAll().firstOrNull { it != null }
        jobs.forEach { it.cancel() }
        result
    }

    /**
     * Perform a single download attempt for a favicon.
     */
    private fun downloadFaviconAttempt(faviconUrl: String): ByteArray? {
        val url = URL(faviconUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = RepositoryConfig.Favicon.CONNECT_TIMEOUT_MS
            readTimeout = RepositoryConfig.Favicon.READ_TIMEOUT_MS
        }

        return try {
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.w("Failed to fetch favicon from $faviconUrl: HTTP ${connection.responseCode}")
                return null
            }

            if (!isImageContentType(connection.contentType)) {
                Log.w("URL is not an image. Content-Type: ${connection.contentType}")
                return null
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            bytes.takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Check if a content type indicates an image.
     * 
     * @param contentType The HTTP Content-Type header value
     * @return true if the content type starts with "image/", false otherwise
     */
    private fun isImageContentType(contentType: String?): Boolean {
        return contentType?.startsWith("image/") == true
    }

    /**
     * Log the completion of a fetch operation.
     */
    private fun logFetchCompletion(stats: FetchStats, startTime: Long) {
        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i("[Favicons] Background fetch completed: ${stats.fetchedCount} fetched, ${stats.failedCount} failed in ${elapsedMs}ms")
    }

    /**
     * Simple statistics tracker for fetch operations.
     */
    private class FetchStats {
        var fetchedCount = 0
            private set
        var failedCount = 0
            private set

        fun incrementFetched() {
            fetchedCount++
        }

        fun incrementFailed() {
            failedCount++
        }
    }
}
