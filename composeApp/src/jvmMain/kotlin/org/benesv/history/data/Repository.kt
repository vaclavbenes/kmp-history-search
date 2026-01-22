package org.benesv.history.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.benesv.history.api.app.FaviconEntity
import org.benesv.history.api.app.Favicons
import org.benesv.history.api.app.History
import org.benesv.history.api.app.HistoryEntity
import org.benesv.history.api.app.Tokens
import org.benesv.history.api.app.TokenEntity
import org.benesv.history.model.BrowserSelection
import org.benesv.history.model.BrowserType
import org.benesv.history.model.Favicon
import org.benesv.history.model.HistoryItem
import org.benesv.history.model.matches
import org.benesv.history.core.Log
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.sql.DriverManager
import java.util.Locale


class HistoryRepository(private val cacheDir: File = defaultCacheDir()) {
    private val chrome = ChromeExtractor()
    private val zen = ZenExtractor()
    private val thorium = ThoriumExtractor()

    private lateinit var database: Database
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dbMutex = Mutex()

    private val faviconsDomainsMutex = Mutex()
    private val faviconsDomains = mutableSetOf<String>()

    private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
    private var currentOffset = 0
    private var hasMoreData = true

    companion object {
        private const val CACHE_DB_FILE = "history.sqlite"
        private const val PAGE_SIZE = 1000

        private const val SQLITE_BUSY_TIMEOUT_MS = 5_000
        private const val FAVICON_CONNECT_TIMEOUT_MS = 5_000
        private const val FAVICON_READ_TIMEOUT_MS = 5_000

        private const val FAVICON_BATCH_SIZE = 5
        private const val FAVICON_DELAY_BETWEEN_REQUESTS_MS = 100L
        private const val FAVICON_RETRY_BASE_DELAY_MS = 500L
        
        private fun defaultCacheDir(): File {
            val osName = System.getProperty("os.name").lowercase()
            val userHome = System.getProperty("user.home")

            val base = when {
                osName.contains("mac") || osName.contains("darwin") -> {
                    // macOS: ~/Library/Application Support/HistorySearch
                    File(userHome, "Library/Application Support/HistorySearch")
                }
                osName.contains("win") -> {
                    // Windows: %LOCALAPPDATA%\HistorySearch
                    val localAppData = System.getenv("LOCALAPPDATA") ?: File(userHome, "AppData/Local").absolutePath
                    File(localAppData, "HistorySearch")
                }
                else -> {
                    // Linux/Unix: ~/.local/share/HistorySearch
                    val xdgDataHome = System.getenv("XDG_DATA_HOME") ?: File(userHome, ".local/share").absolutePath
                    File(xdgDataHome, "HistorySearch")
                }
            }

            // Ensure directory exists before returning
            if (!base.exists()) {
                val created = base.mkdirs()
                if (!created && !base.exists()) {
                    Log.e("Failed to create cache directory at ${base.absolutePath}")
                    throw IllegalStateException("Cannot create cache directory: ${base.absolutePath}")
                }
            }
            return base
        }
    }

    init {
        try {
            val dbFile = File(cacheDir, CACHE_DB_FILE)
            Log.i("[DB] Using database at: ${dbFile.absolutePath}")

            // Ensure parent directory exists
            dbFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            // Create file if it doesn't exist
            if (!dbFile.exists()) {
                dbFile.createNewFile()
                Log.i("[DB] Created new database file")
            }

            val jdbcUrl = "jdbc:sqlite:file:${dbFile.absolutePath}"

            configureSqlite(jdbcUrl)
            database = Database.connect(url = jdbcUrl, driver = "org.sqlite.JDBC")

            scope.launch(Dispatchers.IO) {
                initSchema(dbFile)
                bootstrapIfEmpty()
            }
        } catch (e: Exception) {
            Log.e("[DB] Failed to initialize database: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun configureSqlite(jdbcUrl: String) {
        runCatching {
            DriverManager.getConnection(jdbcUrl).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL;")
                    st.execute("PRAGMA synchronous=NORMAL;")
                    st.execute("PRAGMA busy_timeout=${SQLITE_BUSY_TIMEOUT_MS};")
                }
            }
            Log.i("[DB] SQLite configured successfully")
        }.onFailure { e ->
            Log.e("[DB] Failed to configure SQLite: ${e::class.qualifiedName}: ${e.message}")
            Log.e("[DB] Stack trace: ${e.stackTraceToString()}")
        }
    }

    private suspend fun initSchema(dbFile: File) {
        dbQuery {
            SchemaUtils.createMissingTablesAndColumns(History, Favicons, Tokens)
            Log.i("[DB] Initialized database at ${dbFile.absolutePath}")
        }
    }

    private suspend fun bootstrapIfEmpty() {
        val hasRecords = dbQuery { History.selectAll().limit(1).empty().not() }
        if (hasRecords) {
            Log.i("[DB] Database already initialized, skipping bootstrap")
            loadInitialPage()
            return
        }

        val items = mutableListOf<HistoryItem>()

        if (chrome.isInstalled()) items += chrome.extract(this)
        if (zen.isInstalled()) items += zen.extract(this)
        if (thorium.isInstalled()) items += thorium.extract(this)
        val processedItems = items.deduplicateByUrlAndSortByLastVisit()

        saveToDisk(processedItems)
        loadInitialPage()

        scope.launch(Dispatchers.Default) {
            fetchMissingFaviconsInBackground(processedItems)
        }
    }

    private fun List<HistoryItem>.deduplicateByUrlAndSortByLastVisit(): List<HistoryItem> =
        groupBy { it.url }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastVisit } }
            .sortedByDescending { it.lastVisit }

    private suspend fun loadInitialPage() {
        val data = getDataFromDisk(limit = PAGE_SIZE, offset = 0)
        currentOffset = PAGE_SIZE
        _historyFlow.value = data
    }

    private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        dbMutex.withLock {
            transaction(db = database) { block() }
        }

    // --- Token suggestions persistence ---
    suspend fun saveTokensFromQuery(query: String) {
        val words = query
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length >= 3 }
            .distinct()
        if (words.isEmpty()) return
        val now = System.currentTimeMillis()
        dbQuery {
            words.forEach { w ->
                val existing = TokenEntity.find { Tokens.text eq w }.limit(1).firstOrNull()
                if (existing != null) {
                    existing.frequency = existing.frequency + 1
                    existing.lastUsed = now
                } else {
                    TokenEntity.new {
                        text = w
                        frequency = 1
                        lastUsed = now
                    }
                }
            }
        }
    }

    suspend fun getSuggestions(prefix: String, limit: Int = 5): List<String> {
        val p = prefix.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return emptyList()
        return dbQuery {
            TokenEntity.find { Tokens.text like "$p%" }
                .orderBy(Tokens.frequency to SortOrder.DESC, Tokens.lastUsed to SortOrder.DESC)
                .limit(limit)
                .map { it.text }
        }
    }

    suspend fun refresh(selection: BrowserSelection, deleteFavicons: Boolean = false): List<HistoryItem> {
        val startOfToday = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        dbQuery {
            History.deleteWhere { History.lastVisit greaterEq startOfToday }

            if (deleteFavicons) {
                Log.i("[DB] Deleting all favicons from DB")
                Favicons.deleteAll()
            }
        }

        val extracted = buildList {
            if (selection.matches(BrowserType.Chrome) && chrome.isInstalled()) addAll(chrome.extract(this@HistoryRepository))
            if (selection.matches(BrowserType.Zen) && zen.isInstalled()) addAll(zen.extract(this@HistoryRepository))
            if (selection.matches(BrowserType.Thorium) && thorium.isInstalled()) addAll(thorium.extract(this@HistoryRepository))
        }

        val processed = extracted
            .asSequence()
            .filter { it.lastVisit >= startOfToday }
            .toList()
            .deduplicateByUrlAndSortByLastVisit()

        saveToDisk(processed)
        resetLazyLoading()

        val fromDb = getDataFromDisk(limit = PAGE_SIZE, offset = 0)
        _historyFlow.value = fromDb
        currentOffset = PAGE_SIZE

        scope.launch(Dispatchers.Default) {
            fetchMissingFaviconsInBackground(processed)
        }

        return fromDb
    }

    private suspend fun getDataFromDisk(limit: Int? = null, offset: Int = 0): List<HistoryItem> {
        return runCatching {
            dbQuery {
                val entities = if (limit != null) {
                    HistoryEntity.all()
                        .orderBy(History.lastVisit to SortOrder.DESC)
                        .limit(limit)
                        .drop(offset)
                } else {
                    HistoryEntity.all()
                        .orderBy(History.lastVisit to SortOrder.DESC)
                }

                entities.map { entity ->
                    HistoryItem(
                        browser = BrowserType.valueOf(entity.browser),
                        profile = entity.profile,
                        url = entity.url,
                        title = entity.title,
                        lastVisit = entity.lastVisit,
                        visitCount = entity.visitCount,
                        domain = entity.domain,
                        favicon = entity.favicon?.toModel()
                    )
                }
            }
        }.getOrElse { e ->
            e.printStackTrace()
            emptyList()
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value || !hasMoreData) return

        _isLoadingMore.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val newItems = getDataFromDisk(limit = PAGE_SIZE, offset = currentOffset)
                if (newItems.isEmpty()) {
                    hasMoreData = false
                } else {
                    currentOffset += PAGE_SIZE
                    _historyFlow.value = _historyFlow.value + newItems

                    scope.launch(Dispatchers.Default) {
                        fetchMissingFaviconsInBackground(newItems)
                    }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun resetLazyLoading() {
        currentOffset = 0
        hasMoreData = true
    }

    private suspend fun fetchMissingFaviconsInBackground(items: List<HistoryItem>) {
        Log.i("[Favicons] Starting background favicon fetch for ${items.size} items")
        val startTime = System.currentTimeMillis()
        var fetchedCount = 0
        var failedCount = 0

        val uniqueDomains = items.map { it.domain }.distinct()
        Log.i("[Favicons] Fetching favicons for ${uniqueDomains.size} unique domains")

        // Batch fetch with rate limiting
        uniqueDomains.chunked(FAVICON_BATCH_SIZE).forEach { batch ->
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

                delay(FAVICON_DELAY_BETWEEN_REQUESTS_MS)
            }

            // Reload data from DB to reflect updated favicons
            val updatedData = getDataFromDisk(limit = null, offset = 0)
            _historyFlow.value = updatedData
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i("[Favicons] Background fetch completed: $fetchedCount fetched, $failedCount failed in ${elapsedMs}ms")
    }

    /**
     * Ensure a favicon exists for a domain. If not, fetch from Google S2 service in background
     * and persist to DB, then update history rows and notify observers.
     */
    suspend fun getFaviconByDomain(domain: String, size: Int = 64): Favicon? = withContext(Dispatchers.IO) {
        getFaviconByUrl(domain)?.let { return@withContext it }

        val shouldFetch = faviconsDomainsMutex.withLock { faviconsDomains.add(domain) }
        if (!shouldFetch) return@withContext null

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
            dbQuery { saveFavicon(domain, bytes, overwrite = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            faviconsDomainsMutex.withLock { faviconsDomains.remove(domain) }
        }
    }

    /**
     * Validate that data are correctly stored in the database history.sqlite.
     * Prints simple stats and returns a pair of (historyCount, faviconCount).
     */
    suspend fun validateDatabase(): Pair<Int, Int> {
        return dbQuery {
            val historyCount = History.selectAll().count()
            val faviconCount = Favicons.selectAll().count()
            Log.i("[DB] history rows=$historyCount, favicons rows=$faviconCount in ${cacheDir.absolutePath}/$CACHE_DB_FILE")
            historyCount.toInt() to faviconCount.toInt()
        }
    }

    private suspend fun saveToDisk(items: List<HistoryItem>) {
        runCatching {
            Log.i("Saving to db: ${database.url}")
            dbQuery {
                for (item in items) {
                    val existingHistory = HistoryEntity.find { History.url eq item.url }.firstOrNull()

                    val historyEntity = if (existingHistory != null) {
                        existingHistory.apply {
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

                    if (item.favicon != null) {
                        val faviconEntity = FaviconEntity.find { Favicons.url eq item.favicon.url }.firstOrNull()
                            ?: FaviconEntity.new {
                                url = item.favicon.url
                                imageData = ExposedBlob(item.favicon.imageData ?: ByteArray(0))
                            }
                        historyEntity.favicon = faviconEntity
                    }

                    if (item.favicon == null) {
                        val historyId = historyEntity.id.value
                        scope.launch(Dispatchers.Default) {
                            launchFaviconJob(historyId, item.domain)
                        }
                    }
                }
            }
        }.getOrElse { e ->
            e.printStackTrace()
        }
    }

    private suspend fun launchFaviconJob(historyId: Int, domain: String) {
        try {
            val faviconData = getFaviconByDomain(domain)
            if (faviconData != null) {
                dbQuery {
                    val history = HistoryEntity.findById(historyId)
                    if (history != null) {
                        val faviconEntity = FaviconEntity.find { Favicons.url eq domain }.firstOrNull()
                        if (faviconEntity != null) {
                            history.favicon = faviconEntity
                        }
                    }
                }
                // Update flow to reflect the change
                val updatedData = getDataFromDisk(limit = null, offset = 0)
                _historyFlow.value = updatedData
            }
        } catch (e: Exception) {
            Log.w("[Favicon] Error fetching favicon for $domain: ${e.message}")
        }
    }

    private fun saveFavicon(url: String, imageData: ByteArray, overwrite: Boolean = false): Favicon? {
        val existingFavicon = FaviconEntity.find { Favicons.url eq url }.firstOrNull()

        return if (existingFavicon == null) {
            val newFavicon = FaviconEntity.new {
                this.url = url
                this.imageData = ExposedBlob(imageData)
            }
            newFavicon.toModel()
        } else {
            if (overwrite) {
                existingFavicon.imageData = ExposedBlob(imageData)
            }
            existingFavicon.toModel()
        }
    }

    suspend fun downloadFaviconToByteArray(faviconUrl: String, maxRetries: Int = 2): ByteArray? {
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                val url = URL(faviconUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = FAVICON_CONNECT_TIMEOUT_MS
                connection.readTimeout = FAVICON_READ_TIMEOUT_MS
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
                delay(FAVICON_RETRY_BASE_DELAY_MS * attempt)
            }
        }
        return null
    }

    fun isImageContentType(contentType: String?): Boolean {
        return contentType?.startsWith("image/") == true
    }

    private suspend fun getFaviconByUrl(domain: String): Favicon? {
        return runCatching {
            dbQuery {
                FaviconEntity.find { Favicons.url eq domain }
                    .firstOrNull()
                    ?.toModel()
            }
        }.getOrNull()
    }
}

fun fuzzyFilter(items: List<HistoryItem>, query: String): List<HistoryItem> {
    val tokens = query
        .trim()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    if (tokens.isEmpty()) return items

    fun fieldScore(field: String, token: String, base: Int): Int {
        if (!field.contains(token)) return 0
        var s = base
        // Prefer terms at the start of the field
        if (field.startsWith(token)) s += base / 2
        // Small boost for closer position to the start
        val idx = field.indexOf(token)
        if (idx >= 0) s += (base / 4).coerceAtLeast(1) * (10 - (idx / 10).coerceAtMost(10))
        return s
    }

    fun hasSearchParams(url: String): Boolean {
        return url.contains("?q=") ||
            url.contains("&q=") ||
            url.contains("?search=") ||
            url.contains("&search=") ||
            url.contains("?query=") ||
            url.contains("&query=")
    }

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

        var total = 0
        tokens.forEachIndexed { idx, t ->
            val importance = if (idx == 0) 100 else 35 // first token much more important
            // Prefer domain > url > title for each token
            val domainScore = fieldScore(domain, t, importance * 5)
            val urlScore = fieldScore(url, t, importance * 3)
            val titleScore = fieldScore(title, t, importance * 1)
            total += maxOf(domainScore, urlScore, titleScore)
        }

        // Bonus if tokens appear in URL in the same order they were typed
        if (tokens.size >= 2) {
            var lastIdx = -1
            var inOrder = true
            for (t in tokens) {
                val i = url.indexOf(t)
                if (i < 0 || i < lastIdx) {
                    inOrder = false; break
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

        item to total
    }
        .sortedWith(compareByDescending<Pair<HistoryItem, Int>> { it.second }
            .thenByDescending { it.first.lastVisit }
            .thenByDescending { it.first.visitCount })
        .map { it.first }
}
