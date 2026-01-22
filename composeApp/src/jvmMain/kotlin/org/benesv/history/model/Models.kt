package org.benesv.history.model

import kotlinx.serialization.Serializable

/**
 * Core models representing browser history data.
 */

enum class BrowserType { Chrome, Zen, Thorium }

@Serializable
data class HistoryItem(
    val browser: BrowserType,
    val profile: String,
    val url: String,
    val title: String,
    val lastVisit: Long, // epoch millis
    val visitCount: Int,
    val domain: String,
    val favicon: Favicon? = null,
)

@Serializable
data class Favicon(
    val id: Int,
    val url: String,
    val imageData: ByteArray? = null,
)

@Serializable
sealed class BrowserSelection {
    @Serializable
    data object All: BrowserSelection()
    @Serializable
    data class Single(val browser: BrowserType): BrowserSelection()
}

fun BrowserSelection.matches(type: BrowserType): Boolean = when(this){
    is BrowserSelection.All -> true
    is BrowserSelection.Single -> this.browser == type
}
