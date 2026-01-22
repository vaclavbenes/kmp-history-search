package org.benesv.history.core

import kotlin.apply
import java.io.File
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object Log {
    private val logFile = File(System.getProperty("user.home"), "Library/Logs/HistorySearch/app.log")

    private val logger: Logger by lazy {
        Logger.getLogger("org.benesv.history").apply {
            level = Level.INFO
            useParentHandlers = false

            try {
                logFile.parentFile?.mkdirs()
                handlers.forEach { removeHandler(it) }
                addHandler(FileHandler(logFile.absolutePath, true).apply {
                    level = Level.ALL
                    formatter = object : SimpleFormatter() {
                        override fun format(record: java.util.logging.LogRecord): String {
                            return "[${record.level}] ${record.message}\n"
                        }
                    }
                })
            } catch (e: Exception) {
                // Fallback to default handlers if configuration fails
                System.err.println("Failed to configure logger: ${e.message}")
            }
        }
    }

    fun i(message: String) {
        try {
            logger.info(message)
        } catch (e: Exception) {
            println("[INFO] $message")
        }
    }

    fun w(message: String) {
        try {
            logger.warning(message)
        } catch (e: Exception) {
            System.err.println("[WARNING] $message")
        }
    }

    fun e(message: String) {
        try {
            logger.severe(message)
        } catch (e: Exception) {
            System.err.println("[ERROR] $message")
        }
    }
}
