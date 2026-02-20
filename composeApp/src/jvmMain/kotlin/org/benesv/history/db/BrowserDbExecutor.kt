package org.benesv.history.db

import org.benesv.history.core.Log
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

class BrowserDbExecutor(val dbFile: File, val label: String) {

    val url =  "jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro"
    val driver = "org.sqlite.JDBC"

    fun connect(): DbExecutor {
        Log.i("Connecting to database: $label  path:${dbFile.absolutePath}")
        val database = Database.connect(url = url, driver = driver)
        return DbExecutor(database)
    }
}
