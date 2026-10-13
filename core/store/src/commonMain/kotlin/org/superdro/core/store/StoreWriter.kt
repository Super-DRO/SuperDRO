package org.superdro.core.store

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.coroutines.asFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.superdro.core.store.db.SuperDroDatabase

/**
 * The one writer of the database.
 *
 * Each repository writes through this object. All writes and all read transactions run on one
 * single-thread dispatcher, so two transactions never run at the same time on two threads
 * (SQLDelight issue #4361). A bulk write (an import, an error map, a drawing) is one call of
 * [write], and thus one transaction.
 *
 * Make one [StoreWriter] for the process and give it to each repository. `core:di` binds it as a
 * singleton. A test gives a test dispatcher for [dispatcher] and [readDispatcher].
 *
 * @param database The one database of the process.
 * @param dispatcher The single-thread writer dispatcher. The default is
 *   `storeIoDispatcher.limitedParallelism(1)`, one object for the process.
 * @param readDispatcher The dispatcher of the query flows that read one query.
 */
class StoreWriter(
    val database: SuperDroDatabase,
    val dispatcher: CoroutineDispatcher = defaultWriterDispatcher,
    val readDispatcher: CoroutineDispatcher = storeIoDispatcher,
) {
    /**
     * Runs [block] in one transaction on the writer dispatcher. An exception rolls back all
     * writes of [block] and goes to the caller.
     */
    suspend fun <T> write(block: TransactionWithReturn<T>.(SuperDroDatabase) -> T): T =
        withContext(dispatcher) { database.transactionWithResult { block(database) } }

    /**
     * Reads with more than one query in one transaction on the writer dispatcher, so that the
     * result comes from one state of the database.
     */
    suspend fun <T> read(block: (SuperDroDatabase) -> T): T =
        withContext(dispatcher) { database.transactionWithResult { block(database) } }

    /**
     * A flow of a value that more than one query gives. The flow calls [reader] in one transaction
     * at the start and after each change of a table of [queries]. It gives a new value only when
     * the value changes.
     */
    fun <T> observe(vararg queries: Query<*>, reader: (SuperDroDatabase) -> T): Flow<T> =
        combine(queries.map { query -> query.asFlow() }) { }
            .conflate()
            .map { read(reader) }
            .distinctUntilChanged()
}

/** The writer dispatcher of the process: one object, so that it has one thread. */
private val defaultWriterDispatcher: CoroutineDispatcher by lazy { storeIoDispatcher.limitedParallelism(1) }
