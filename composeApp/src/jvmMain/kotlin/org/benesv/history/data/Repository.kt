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
import org.benesv.history.core.Log
import org.benesv.history.core.Pager
import org.benesv.history.core.defaultCacheDir
import org.benesv.history.dao.FaviconDao
import org.benesv.history.dao.HistoryDao
import org.benesv.history.dao.TokenDao
import org.benesv.history.db.DbConnector
import org.benesv.history.extractor.ChromeExtractor
import org.benesv.history.extractor.HistoryExtractor
import org.benesv.history.extractor.ThoriumExtractor
import org.benesv.history.extractor.ZenExtractor
import org.benesv.history.manager.FaviconManager
import org.benesv.history.manager.FaviconOrchestrator
import org.benesv.history.model.BrowserSelection
import org.benesv.history.model.HistoryItem
import org.benesv.history.model.matches
import java.io.File
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

    private val historyDao: HistoryDao by lazy { HistoryDao(dbConnector.db) }
    private val faviconDao: FaviconDao by lazy { FaviconDao(dbConnector.db) }
    private val tokenDao: TokenDao by lazy { TokenDao(dbConnector.db) }

    private val pager: Pager<HistoryItem> by lazy {
        Pager(RepositoryConfig.PAGE_SIZE) { limit, offset ->
            historyDao.page(limit, offset)
        }
    }

    private val faviconManager: FaviconManager by lazy {
        FaviconManager(faviconDao)
    }

    private val faviconOrchestrator: FaviconOrchestrator by lazy {
        FaviconOrchestrator(faviconManager, historyDao, faviconDao, dbConnector, scope)
    }

    private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    init {
        try {
            scope.launch(Dispatchers.IO) {
                dbConnector.prepareDb()
                dbConnector.initSchema()

                initializeHistoryData()
            }
        } catch (e: Exception) {
            Log.e("Failed to initialize database: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun initializeHistoryData() =  coroutineScope {
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

        val freshData = pager.loadFirst()
        _historyFlow.value = freshData

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

        faviconOrchestrator.fetchAndUpdateHistory(processed) { updatedData ->
            _historyFlow.value = updatedData
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

                    faviconOrchestrator.fetchAndUpdateHistory(newItems) { updatedData ->
                        _historyFlow.value = updatedData
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
                    faviconOrchestrator.launchFaviconJob(historyId, item.domain)
                }
            }
        }.getOrElse { e ->
            e.printStackTrace()
        }
    }

}
