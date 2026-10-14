package org.superdro.core.store.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** A driver that keeps the text of each select, so that a test can count the reads. */
internal class CountingDriver(private val driver: SqlDriver) : SqlDriver by driver {
    private val selects = mutableListOf<String>()

    /** The number of selects since the last [clear] that contain [text]. */
    fun count(text: String): Int = synchronized(selects) { selects.count { sql -> sql.contains(text) } }

    /** Forgets the selects. */
    fun clear() = synchronized(selects) { selects.clear() }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        synchronized(selects) { selects += sql }
        return driver.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}
