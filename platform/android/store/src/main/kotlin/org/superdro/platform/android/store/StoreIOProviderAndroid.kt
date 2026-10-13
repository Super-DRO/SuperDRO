package org.superdro.platform.android.store

import android.content.Context
import android.net.Uri
import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import org.superdro.core.store.StoreIOProvider

/**
 * The Android [StoreIOProvider]. The files are in the files directory of
 * the app, which only the app can read. The app binds it with `overrides = true` over the JVM
 * binding of `core:di`.
 *
 * A store name can have a folder, for example `images/<uuid>.jpg` or `wizard/<id>-x.json`. The
 * provider makes the folder at the first write.
 */
class StoreIOProviderAndroid(
    private val context: Context,
) : StoreIOProvider {
    override fun exists(storeName: String): Boolean = fileOf(storeName).exists()

    override fun sinkFor(storeName: String): RawSink =
        SyncOnCloseOutputStream(FileOutputStream(fileOf(storeName).withParentDirectory())).asSink()

    override fun sourceFor(storeName: String): RawSource = fileOf(storeName).inputStream().asSource()

    override fun sourceForUri(uri: String): RawSource {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw IOException("Cannot open an input stream for the URI $uri")
        return stream.asSource()
    }

    override fun pathFor(storeName: String): String = fileOf(storeName).absolutePath

    override fun delete(storeName: String) {
        // The interface says "never throws", so a failure goes only to the log.
        try {
            val file = fileOf(storeName)
            if (file.exists() && !file.delete()) {
                log.w { "Cannot delete $storeName" }
            }
        } catch (error: SecurityException) {
            log.w(error) { "Cannot delete $storeName" }
        }
    }

    /**
     * A rename in the files directory of the app. It is atomic and replaces the file at [to], as
     * the rename of `android.util.AtomicFile`.
     */
    override fun move(from: String, to: String) {
        val source = fileOf(from)
        val target = fileOf(to).withParentDirectory()
        if (!source.renameTo(target)) {
            throw IOException("Cannot rename $from to $to")
        }
    }

    private fun fileOf(storeName: String): File = File(context.filesDir, storeName)

    private fun File.withParentDirectory(): File = also { file ->
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            log.e { "Cannot make the directory ${parent.absolutePath}" }
        }
    }

    /**
     * Writes to a file, and at close writes the bytes to the disk before the file closes. A move
     * after the close then cannot install a file whose bytes are only in the cache of the OS.
     */
    private class SyncOnCloseOutputStream(private val out: FileOutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun flush() = out.flush()

        override fun close() {
            out.use { stream ->
                stream.flush()
                stream.fd.sync()
            }
        }
    }

    private companion object {
        val log = Arbor.tag("StoreIOProviderAndroid")
    }
}
