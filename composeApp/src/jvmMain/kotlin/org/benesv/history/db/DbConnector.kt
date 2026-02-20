package org.benesv.history.db

import org.benesv.history.api.app.Favicons
import org.benesv.history.api.app.History
import org.benesv.history.api.app.Tokens
import org.benesv.history.core.Log
import org.benesv.history.data.RepositoryConfig
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.io.File
import java.sql.DriverManager


/**
 * Executor for connecting to SQLite databases.
 * Handles connections to both browser history databases (read-only) and application databases (read-write).
 * 
 * @param dbFile The SQLite database file to connect to
 * @param label A label identifying the browser/profile for logging purposes
 * @param readOnly If true, opens a database in read-only mode; if false, allows read-write operations (default: true)
 */
class DbConnector(val dbFile: File, val label: String, val readOnly: Boolean = true) {

    /** JDBC connection URL for SQLite database access */
    val url = if (readOnly) {
        "jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro"
    } else {
        "jdbc:sqlite:${dbFile.absolutePath}"
    }

    /** SQLite JDBC driver class */
    val driver = "org.sqlite.JDBC"


    val db: DbExecutor by lazy {
        val mode = if (readOnly) "read-only" else "read-write"
        Log.i("Connecting to database: $label ($mode)  path:${dbFile.absolutePath}")
        DbExecutor(Database.connect(url = url, driver = driver))
    }

    suspend fun initSchema() {
        db.query {
            Log.i("Initializing database schema")
            SchemaUtils.createMissingTablesAndColumns(History, Favicons, Tokens)
        }
    }

    suspend fun <T>query(block: Transaction.() -> T) {
        db.query(block)
    }

    fun prepareDb() {
        runCatching {
            DriverManager.getConnection(url).use { conn ->
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

}
