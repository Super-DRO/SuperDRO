package org.superdro.core.store.json

import com.toxicbakery.logging.Arbor
import kotlin.jvm.Synchronized
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink
import org.superdro.core.store.CORRUPT_SUFFIX
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.readEntry
import org.superdro.core.store.writeAtomically

/**
 * A JSON file store with the SnapshotPB pattern.
 *
 * - The store is the single source of truth of its file. It
 *   holds the value in [state], a [StateFlow], and changes it with [MutableStateFlow.update].
 * - A [Mutex] serializes the file IO. Each write is atomic: the full JSON goes to a temporary
 *   entry, and a move then replaces the file.
 * - A reader that reads one time calls [awaitLoaded] first. Before the load, [state] holds the
 *   defaults, and a read cannot tell them from an empty file.
 * - An [update] before the load completes is not lost: the load applies it again on top of the
 *   value from the file. No write occurs before the load.
 * - A file that does not decode moves to `<file>.corrupt-<ms>`, and the store starts with the
 *   defaults. When that move fails, the store writes nothing in this process, so a write cannot
 *   destroy the only copy of the old data.
 * - Each field of the model has a default, so an old file without a new field loads. The model has
 *   the field `schemaVersion`.
 *
 * @param fileName The store name of the file, for example `app-settings.json`.
 * @param defaultValue The value of a new install, or after a file that does not decode.
 * @param dispatcher The dispatcher of the file IO. A test gives a test dispatcher.
 * @param clock Gives the time in the name of a file that does not decode.
 */
abstract class JsonFileStore<T : Any>(
    private val storeIO: StoreIOProvider,
    private val fileName: String,
    private val serializer: KSerializer<T>,
    defaultValue: T,
    private val json: Json,
    dispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val log = Arbor.tag("JsonFileStore")

    private val scope = CoroutineScope(
        dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            log.e(error) { "The file IO of $fileName failed." }
        }
    )
    private val lock = Mutex()

    /** True when a file that does not decode could not move aside. Read and written only under [lock]. */
    private var writesBlocked = false

    /** The updates before the load completed. Read and written only in a synchronized function. */
    private val pendingBeforeLoad = mutableListOf<(T) -> T>()
    private var loadApplied = false

    private val initialLoad = CompletableDeferred<Unit>()
    private var lastWrite: Job? = null

    private val mutableState = MutableStateFlow(defaultValue)

    /** The current value. It is the default value until the load completes. */
    val state: StateFlow<T> = mutableState.asStateFlow()

    private val mutableIsLoaded = MutableStateFlow(false)

    /** False until the value of the file is in [state]. */
    val isLoaded: StateFlow<Boolean> = mutableIsLoaded.asStateFlow()

    /** Suspends until the value of the file is in [state]. */
    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    /**
     * Changes the value with [transform] and writes the file. [transform] must have no side
     * effect, because the load can apply it a second time on top of the value of the file.
     */
    @Synchronized
    fun update(transform: (T) -> T) {
        mutableState.update(transform)
        if (!loadApplied) pendingBeforeLoad += transform
        lastWrite = write()
    }

    /** Suspends until the last write that [update] started is complete. */
    suspend fun awaitWrites() {
        currentWrite()?.join()
    }

    @Synchronized
    private fun currentWrite(): Job? = lastWrite

    @Synchronized
    private fun applyLoaded(loaded: T?) {
        if (loaded != null) {
            mutableState.update { pendingBeforeLoad.fold(loaded) { value, transform -> transform(value) } }
        }
        pendingBeforeLoad.clear()
        loadApplied = true
    }

    private fun load() = scope.launch {
        try {
            val loaded = lock.withLock { readFile() }
            applyLoaded(loaded)
        } finally {
            initialLoad.complete(Unit)
            mutableIsLoaded.value = true
        }
    }

    /** Reads the file, or gives null when the file is not there or does not decode. Call only under [lock]. */
    private fun readFile(): T? {
        if (!storeIO.exists(fileName)) return null
        return try {
            storeIO.readEntry(fileName) { source -> json.decodeFromSource(serializer, source) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            moveUnreadableFileAside(error)
            null
        }
    }

    private fun write(): Job = scope.launch {
        // No write before the load: a write at the start must not replace the file before the
        // store read it.
        initialLoad.await()
        lock.withLock {
            if (writesBlocked) {
                log.e { "No write of $fileName: the file that does not decode could not move aside." }
                return@withLock
            }
            val value = state.value
            storeIO.writeAtomically(fileName) { sink -> json.encodeToSink(serializer, value, sink) }
        }
    }

    /** Moves a file that does not decode to `<file>.corrupt-<ms>`. Call only under [lock]. */
    private fun moveUnreadableFileAside(error: Exception) {
        val asideName = "$fileName$CORRUPT_SUFFIX${clock.now().toEpochMilliseconds()}"
        log.e(error) { "Cannot decode $fileName. It moves to $asideName." }
        try {
            storeIO.move(fileName, asideName)
        } catch (moveError: CancellationException) {
            throw moveError
        } catch (moveError: Exception) {
            writesBlocked = true
            log.e(moveError) { "Cannot move $fileName aside. The store writes nothing in this process." }
        }
    }

    init {
        load()
    }
}
