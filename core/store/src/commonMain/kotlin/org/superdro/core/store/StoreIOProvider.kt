package org.superdro.core.store

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * The file access of the JSON stores and the plain files (a platform contract).
 * `StoreIOProviderJvm` is in `jvmMain`. `StoreIOProviderAndroid` is in `platform:android:store`.
 *
 * A store name is a path relative to the files of the app. It can have a folder with `/`, for
 * example `images/<uuid>.jpg` (the reference images) or `error_map_wizard/adapter_<id>_X.emw` (the
 * error map wizard sessions). [sinkFor] and [move] make the folder when it is not there.
 */
interface StoreIOProvider {
    fun exists(storeName: String): Boolean
    fun sinkFor(storeName: String): RawSink
    fun sourceFor(storeName: String): RawSource
    fun sourceForUri(uri: String): RawSource
    fun pathFor(storeName: String): String

    /**
     * Removes the stored entry if it is there. This function never throws: a store that cannot
     * delete, or an entry that is not there, must not fail the operation of the caller.
     */
    fun delete(storeName: String)

    /**
     * Moves the entry [from] to [to], and replaces an entry at [to].
     *
     * A platform with a file system must do an atomic rename, so that a reader sees the full old
     * entry or the full new entry, and never a part of one. A store writes its file to a temporary
     * entry and then moves it, so a process stop during a write cannot leave a short file.
     * Throws when the move fails. The entry at [to] then does not change.
     *
     * The default copies the bytes and then deletes [from]. It is correct for a store in memory.
     */
    fun move(from: String, to: String) {
        sourceFor(from).buffered().use { source ->
            sinkFor(to).buffered().use { sink -> source.transferTo(sink) }
        }
        delete(from)
    }
}
