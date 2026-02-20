package org.benesv.history.db

import org.benesv.history.core.Log
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File


/**
 * Executor for connecting to SQLite databases.
 * Handles connections to both browser history databases (read-only) and application databases (read-write).
 * 
 * @param dbFile The SQLite database file to connect to
 * @param label A label identifying the browser/profile for logging purposes
 * @param readOnly If true, opens database in read-only mode; if false, allows read-write operations (default: true)
 */
class DbConnector(val dbFile: File, val label: String, val readOnly: Boolean = true) {

    lateinit var db: DbExecutor

    /** JDBC connection URL for SQLite database access */
    val url = if (readOnly) {
        "jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro"
    } else {
        "jdbc:sqlite:${dbFile.absolutePath}"
    }

    /** SQLite JDBC driver class */
    val driver = "org.sqlite.JDBC"

    /**
     * Establishes a connection to the database.
     * Logs the connection attempt with database label and path.
     * 
     * @return A DbExecutor instance wrapping the connected database
     */
    fun connect(): DbExecutor {
        val mode = if (readOnly) "read-only" else "read-write"
        Log.i("Connecting to database: $label ($mode)  path:${dbFile.absolutePath}")
        return DbExecutor(Database.connect(url = url, driver = driver))
    }

}
