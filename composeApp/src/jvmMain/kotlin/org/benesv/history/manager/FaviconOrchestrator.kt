package org.benesv.history.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.benesv.history.core.Log
import org.benesv.history.dao.FaviconDao
import org.benesv.history.dao.HistoryDao
import org.benesv.history.db.DbConnector
import org.benesv.history.model.HistoryItem

/**
 * Orchestrates favicon operations for history items.
 * 
 * This orchestrator centralizes all favicon-related operations including:
 * - Background fetching of missing favicons for history items
 * - Updating individual history items with fetched favicons
 * - Coordinating between FaviconManager, FaviconDao, and HistoryDao
 * 
 * The orchestrator acts as a higher-level coordinator that uses FaviconManager
 * for the actual fetching logic and manages the database updates.
 * 
 * @property faviconManager Manager responsible for fetching favicon data
 * @property historyDao Data access object for history items
 * @property faviconDao Data access object for favicon persistence
 * @property dbConnector Database connector for executing queries
 * @property scope Coroutine scope for launching background operations
 */
class FaviconOrchestrator(
    private val faviconManager: FaviconManager,
    private val historyDao: HistoryDao,
    private val faviconDao: FaviconDao,
    private val dbConnector: DbConnector,
    private val scope: CoroutineScope
) {
    /**
     * Fetch missing favicons for a list of history items and update observers.
     * 
     * This method launches a background coroutine that:
     * 1. Uses FaviconManager to fetch missing favicons for the provided items
     * 2. Calls the onUpdate callback after each batch to notify observers
     * 
     * The operation runs on Dispatchers.Default to avoid blocking the main thread.
     * 
     * @param items List of history items to fetch favicons for
     * @param onUpdate Callback invoked after each batch to provide updated data
     */
    fun fetchAndUpdateHistory(
        items: List<HistoryItem>,
        onUpdate: (List<HistoryItem>) -> Unit
    ) {
        scope.launch(Dispatchers.Default) {
            faviconManager.fetchMissingFaviconsInBackground(items) {
                val updatedData = historyDao.all()
                onUpdate(updatedData)
            }
        }
    }

    /**
     * Fetch and associate a favicon with a specific history item.
     * 
     * This method:
     * 1. Fetches the favicon for the given domain using FaviconManager
     * 2. Retrieves the corresponding history item by ID
     * 3. Finds the favicon entity in the database
     * 4. Updates the history item to reference the favicon entity
     * 
     * @param historyId The ID of the history item to update
     * @param domain The domain to fetch the favicon for
     * @return true if the favicon was successfully fetched and associated, false otherwise
     */
    suspend fun fetchForHistoryItem(historyId: Int, domain: String): Boolean {
        return try {
            // Fetch the favicon data (this will save it to DB if successful)
            val faviconData = faviconManager.getFaviconByDomain(domain) ?: return false
            
            // Get the history entity
            val history = historyDao.findById(historyId) ?: return false
            
            // Get the favicon entity that was just saved
            val faviconEntity = faviconDao.findEntityByUrl(domain) ?: return false
            
            // Associate the favicon with the history item
            dbConnector.query {
                history.favicon = faviconEntity
            }
            
            true
        } catch (e: Exception) {
            Log.w("[Favicon] Error fetching favicon for $domain: ${e.message}")
            false
        }
    }

    /**
     * Launch a background job to fetch and associate a favicon with a history item.
     * 
     * This is a fire-and-forget operation that doesn't block the caller.
     * Useful when you want to trigger favicon fetching without waiting for completion.
     * 
     * @param historyId The ID of the history item to update
     * @param domain The domain to fetch the favicon for
     */
    fun launchFaviconJob(historyId: Int, domain: String) {
        scope.launch(Dispatchers.Default) {
            fetchForHistoryItem(historyId, domain)
        }
    }
}
