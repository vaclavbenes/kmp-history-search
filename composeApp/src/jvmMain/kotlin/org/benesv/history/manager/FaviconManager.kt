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
 * @property scope Coroutine scope for background operations
 */
class FaviconManager(
    private val faviconDao: FaviconDao,
    private val scope: CoroutineScope
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
        val startTime = System.currentTimeMillis()
        var fetchedCount = 0
        var failedCount = 0

        val uniqueDomains = items.map { it.domain }.distinct()
        Log.i("[Favicons] Fetching favicons for ${uniqueDomains.size} unique domains")

        // Batch fetch with rate limiting
        uniqueDomains.chunked(RepositoryConfig.Favicon.BATCH_SIZE).forEach { batch ->
            batch.forEach { domain ->
                try {
                    val favicon = getFaviconByDomain(domain)
                    if (favicon?.imageData != null) {
                        fetchedCount++
                        Log.i("[Favicons] ✓ Fetched favicon for $domain (ID: ${favicon.id})")
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                    Log.w("[Favicons] ✗ Failed to fetch favicon for $domain: ${e.message}")
                }

                delay(RepositoryConfig.Favicon.DELAY_BETWEEN_REQUESTS_MS)
            }

            // Notify observer to reload data from DB to reflect updated favicons
            onUpdate()
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i("[Favicons] Background fetch completed: $fetchedCount fetched, $failedCount failed in ${elapsedMs}ms")
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
        val shouldFetch = faviconsDomainsMutex.withLock { faviconsDomains.add(domain) }
        if (!shouldFetch) return@withContext null

        /**
         * Try to fetch the first non-empty favicon from a list of candidate URLs.
         * Launches parallel requests and returns as soon as the first successful download completes.
         */
        suspend fun fetchFirstNonEmpty(candidates: List<String>): ByteArray? = coroutineScope {
            val jobs = candidates.map { url ->
                async {
                    runCatching { downloadFaviconToByteArray(url) }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                }
            }
            val result = jobs.awaitAll().firstOrNull { it != null }
            jobs.forEach { it.cancel() }
            result
        }

        return@withContext try {
            val candidates = FaviconExtractor.getCandidateFaviconUrls(domain, size)
            val bytes: ByteArray = fetchFirstNonEmpty(candidates) ?: return@withContext null
            faviconDao.save(domain, bytes, overwrite = true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            faviconsDomainsMutex.withLock { faviconsDomains.remove(domain) }
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
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                val url = URL(faviconUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = RepositoryConfig.Favicon.CONNECT_TIMEOUT_MS
                connection.readTimeout = RepositoryConfig.Favicon.READ_TIMEOUT_MS
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w("Failed to fetch favicon from $faviconUrl: HTTP $responseCode")
                    connection.disconnect()
                    return null
                }

                // Check if the content type is an image
                val contentType = connection.contentType
                if (!isImageContentType(contentType)) {
                    Log.w("URL is not an image. Content-Type: $contentType")
                    connection.disconnect()
                    return null
                }

                val bytes = connection.inputStream.use { inputStream ->
                    inputStream.readBytes()
                }
                connection.disconnect()

                return if (bytes.isNotEmpty()) bytes else null
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) {
                    Log.w("Failed to download favicon from $faviconUrl after $maxRetries retries: ${e.message}")
                    return null
                }
                Log.i("Retry $attempt/$maxRetries for $faviconUrl due to: ${e.message}")
                delay(RepositoryConfig.Favicon.RETRY_BASE_DELAY_MS * attempt)
            }
        }
        return null
    }

    /**
     * Check if a content type indicates an image.
     * 
     * @param contentType The HTTP Content-Type header value
     * @return true if the content type starts with "image/", false otherwise
     */
    fun isImageContentType(contentType: String?): Boolean {
        return contentType?.startsWith("image/") == true
    }

    /**
     * Cleanup method to be called when the manager is no longer needed.
     * Currently, cleanup is handled by the provided CoroutineScope.
     */
    fun cleanup() {
        // Scope cancellation is handled by the caller
        // This method is here for future cleanup logic if needed
    }
}
