package org.benesv.history.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.benesv.history.api.app.Favicons
import org.benesv.history.api.app.History
import org.benesv.history.api.app.Tokens
import org.benesv.history.core.Log
import org.benesv.history.core.Pager
import org.benesv.history.core.defaultCacheDir
import org.benesv.history.dao.FaviconDao
import org.benesv.history.dao.HistoryDao
import org.benesv.history.dao.TokenDao
import org.benesv.history.db.DbConnector
import org.benesv.history.db.DbExecutor
import org.benesv.history.extractor.ChromeExtractor
import org.benesv.history.extractor.HistoryExtractor
import org.benesv.history.extractor.ThoriumExtractor
import org.benesv.history.extractor.ZenExtractor
import org.benesv.history.manager.FaviconManager
import org.benesv.history.model.BrowserSelection
import org.benesv.history.model.Favicon
import org.benesv.history.model.HistoryItem
import org.benesv.history.model.matches
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.io.File
import java.sql.DriverManager
import java.util.Locale


class HistoryRepository(
    private val cacheDir: File = defaultCacheDir(),
    private val extractors: List<HistoryExtractor> = listOf(
        ChromeExtractor(),
        ZenExtractor(),
        ThoriumExtractor()
    )
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dbFile: File by lazy {
        File(cacheDir, RepositoryConfig.CACHE_DB_FILE).also { file ->
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            if (!file.exists()) {
                file.createNewFile()
                Log.i("Creating new database file")
            }
        }
    }

    private val dbConnector by lazy { DbConnector(dbFile, "[History]", readOnly = false) }
    private val db by lazy { dbConnector.connect() }

    private val historyDao: HistoryDao by lazy { HistoryDao(db) }
    private val faviconDao: FaviconDao by lazy { FaviconDao(db) }
    private val tokenDao: TokenDao by lazy { TokenDao(db) }

    private val pager: Pager<HistoryItem> by lazy {
        Pager(RepositoryConfig.PAGE_SIZE) { limit, offset ->
            historyDao.page(limit, offset)
        }
    }

    private val faviconManager: FaviconManager by lazy {
        FaviconManager(faviconDao, scope)
    }

    private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    init {
        try {
            scope.launch(Dispatchers.IO) {
                db.initSchema()
                // Load something ASAP if the DB already has records,
                // while in parallel we refresh the whole history from browsers (to catch new data).
                bootstrapAndRefreshInParallel()
            }
        } catch (e: Exception) {
            Log.e("Failed to initialize database: ${e.message}")
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
                    st.execute("PRAGMA busy_timeout=${RepositoryConfig.Sqlite.BUSY_TIMEOUT_MS};")
                }
            }
        }.onFailure { e ->
            Log.e("Failed to configure SQLite: ${e::class.qualifiedName}: ${e.message}")
            Log.e("Stack trace: ${e.stackTraceToString()}")
        }
    }

    private suspend fun initSchema(dbFile: File) {
        db.query {
            Log.i("Initializing database schema")
            SchemaUtils.createMissingTablesAndColumns(History, Favicons, Tokens)
        }
    }

    private suspend fun bootstrapAndRefreshInParallel() = coroutineScope {
        val hasRecordsDeferred = async {
            historyDao.hasRecords()
        }

        val extractedDeferred = async {
            extractWholeHistoryFromBrowsers()
        }

        val hasRecords = hasRecordsDeferred.await()
        if (hasRecords) {
            // Show cached data immediately
            loadInitialPage()
        }

        val extracted = extractedDeferred.await()
        val processed = extracted.deduplicateByUrlAndSortByLastVisit()

        if (!hasRecords && processed.isEmpty()) {
            // Nothing in DB and nothing extracted -> keep empty state
            Log.i("No cached records and no extracted history found")
            return@coroutineScope
        }

        if (processed.isNotEmpty()) {
            saveToDisk(processed)
        }

        // Reset pagination and re-load the first page to reflect any newly saved data
        val freshData = pager.loadFirst()
        _historyFlow.value = freshData

//        scope.launch(Dispatchers.Default) {
//            fetchMissingFaviconsInBackground(processed.ifEmpty { _historyFlow.value })
//        }
    }

    private suspend fun extractWholeHistoryFromBrowsers(): List<HistoryItem> =
        extractors
            .filter { it.isInstalled() }
            .flatMap { it.extract(this) }

    private fun List<HistoryItem>.deduplicateByUrlAndSortByLastVisit(): List<HistoryItem> =
        groupBy { it.url }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastVisit } }
            .sortedByDescending { it.lastVisit }

    private suspend fun loadInitialPage() {
        val data = pager.loadFirst()
        Log.i("Loading initial page size: ${data.size}")
        _historyFlow.value = data
    }

    suspend fun saveTokensFromQuery(query: String) {
        tokenDao.saveTokens(query)
    }

    suspend fun getSuggestions(prefix: String, limit: Int = 5): List<String> {
        val p = prefix.trim().lowercase(Locale.ROOT)
        if (p.isEmpty()) return emptyList()
        return tokenDao.suggestions(p, limit)
    }

    suspend fun refresh(selection: BrowserSelection, deleteFavicons: Boolean = false): List<HistoryItem> {
        val startOfToday = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        historyDao.deleteSince(startOfToday)

        if (deleteFavicons) {
            Log.i("[DB] Deleting all favicons from DB")
            faviconDao.deleteAll()
        }

        val extracted = extractors
            .filter { it.isInstalled() && selection.matches(it.browserType) }
            .flatMap { it.extract(this@HistoryRepository) }

        val processed = extracted
            .asSequence()
            .filter { it.lastVisit >= startOfToday }
            .toList()
            .deduplicateByUrlAndSortByLastVisit()

        saveToDisk(processed)
        resetLazyLoading()

        val fromDb = pager.loadFirst()
        _historyFlow.value = fromDb

        scope.launch(Dispatchers.Default) {
            faviconManager.fetchMissingFaviconsInBackground(processed) {
                val updatedData = historyDao.all()
                _historyFlow.value = updatedData
            }
        }

        return fromDb
    }

    fun loadMore() {
        if (_isLoadingMore.value || !pager.hasMore()) return

        _isLoadingMore.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val newItems = pager.loadNext()
                if (newItems.isNotEmpty()) {
                    _historyFlow.value = _historyFlow.value + newItems

                    scope.launch(Dispatchers.Default) {
                        faviconManager.fetchMissingFaviconsInBackground(newItems) {
                            val updatedData = historyDao.all()
                            _historyFlow.value = updatedData
                        }
                    }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun resetLazyLoading() {
        pager.reset()
    }

    /**
     * Validate that data are correctly stored in the database history.sqlite.
     * Prints simple stats and returns a pair of (historyCount, faviconCount).
     */
    suspend fun validateDatabase(): Pair<Int, Int> {
        val historyCount = historyDao.count().toInt()
        val faviconCount = faviconDao.count().toInt()
        Log.i("[DB] history rows=$historyCount, favicons rows=$faviconCount in ${cacheDir.absolutePath}/${RepositoryConfig.CACHE_DB_FILE}")
        return historyCount to faviconCount
    }

    private suspend fun saveToDisk(items: List<HistoryItem>) {
        runCatching {
            Log.i("Saving to db: ${dbConnector.url}")
            for (item in items) {
                val faviconEntity = if (item.favicon != null) {
                    faviconDao.findEntityByUrl(item.favicon.url)
                } else null

                val historyEntity = historyDao.upsert(item, faviconEntity)

                // If no favicon, launch a background job to fetch it
                if (item.favicon == null) {
                    val historyId = historyEntity.id.value
                    scope.launch(Dispatchers.Default) {
                        launchFaviconJob(historyId, item.domain)
                    }
                }
            }
        }.getOrElse { e ->
            e.printStackTrace()
        }
    }

    private suspend fun launchFaviconJob(historyId: Int, domain: String) {
        try {
            val faviconData = faviconManager.getFaviconByDomain(domain)
            if (faviconData != null) {
                val history = historyDao.findById(historyId)
                if (history != null) {
                    val faviconEntity = faviconDao.findEntityByUrl(domain)
                    if (faviconEntity != null) {
                        db.query {
                            history.favicon = faviconEntity
                        }
                    }
                }
                // Update flow to reflect the change
                val updatedData = historyDao.all()
                _historyFlow.value = updatedData
            }
        } catch (e: Exception) {
            Log.w("[Favicon] Error fetching favicon for $domain: ${e.message}")
        }
    }
}
