package org.benesv.history.core

import java.io.File

/**
 * Returns the platform-specific cache directory for the application.
 * Creates the directory if it doesn't exist.
 *
 * Platform-specific locations:
 * - macOS: ~/Library/Application Support/HistorySearch
 * - Windows: %LOCALAPPDATA%\HistorySearch
 * - Linux/Unix: ~/.local/share/HistorySearch (or $XDG_DATA_HOME/HistorySearch)
 *
 * @return The cache directory file
 * @throws IllegalStateException if the directory cannot be created
 */
fun defaultCacheDir(): File {
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
