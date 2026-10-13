package org.superdro.core.store

import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The JVM [StoreIOProvider]: the tests and a later desktop app.
 *
 * A relative store name resolves against [baseDir], a directory of the user (see
 * [appDataDirectory]). It does not resolve against the working directory of the process. The
 * provider makes [baseDir] at the first use. A store name can have a folder, for example
 * `images/<uuid>.jpg` or `wizard/<id>-x.json`. The provider makes the folder at the first write.
 *
 * @param baseDir The directory of the stored files. A test gives a temporary directory.
 */
class StoreIOProviderJvm(
    private val baseDir: File = appDataDirectory(),
) : StoreIOProvider {

    /** [baseDir], made at the first access. */
    private val preparedBaseDir: File by lazy { prepareBaseDir() }

    override fun exists(storeName: String): Boolean =
        resolve(storeName).exists()

    override fun sinkFor(storeName: String): RawSink =
        SyncOnCloseOutputStream(FileOutputStream(resolve(storeName).withParentDirectory())).asSink()

    override fun sourceFor(storeName: String): RawSource =
        SystemFileSystem.source(Path(resolve(storeName).path))

    override fun sourceForUri(uri: String): RawSource =
        SystemFileSystem.source(path = SystemFileSystem.resolve(Path(uri)))

    override fun pathFor(storeName: String): String =
        resolve(storeName).absolutePath

    override fun delete(storeName: String) {
        runCatching { Files.deleteIfExists(resolve(storeName).toPath()) }
            .onFailure { error -> log.w(error) { "Cannot delete $storeName" } }
    }

    override fun move(from: String, to: String) {
        val source = resolve(from).toPath()
        val target = resolve(to).withParentDirectory().toPath()
        try {
            // A rename on the same file system. It replaces the target on POSIX and on Windows.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            log.w(error) { "No atomic rename for $to. A plain replace is used." }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resolve(storeName: String): File {
        val file = File(storeName)
        return if (file.isAbsolute) file else File(preparedBaseDir, storeName)
    }

    /** Makes the folder of a store name with a `/`, for example `images/<uuid>.jpg`. */
    private fun File.withParentDirectory(): File = also { file ->
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            log.e { "Cannot make the directory ${parent.absolutePath}" }
        }
    }

    private fun prepareBaseDir(): File {
        if (!baseDir.isDirectory && !baseDir.mkdirs()) {
            log.e { "Cannot make the application data directory ${baseDir.absolutePath}" }
        }
        return baseDir
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
        val log = Arbor.tag("StoreIOProviderJvm")
    }
}

/** The name of the application data directory on macOS and Windows. */
private const val APP_DIRECTORY_NAME = "SuperDRO"

/** The name of the application data directory on Linux, in the XDG style. */
private const val APP_DIRECTORY_NAME_XDG = "superdro"

/**
 * The application data directory of the user for [osName]:
 * - macOS: `~/Library/Application Support/SuperDRO`
 * - Windows: `%APPDATA%\SuperDRO` (`~\AppData\Roaming` when `APPDATA` is not set)
 * - Linux and other systems: `$XDG_DATA_HOME/superdro` (`~/.local/share` when `XDG_DATA_HOME`
 *   is not set or is not absolute)
 */
internal fun appDataDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home").orEmpty(),
): File {
    val os = osName.lowercase()
    return when {
        // Examine "mac" and "darwin" first, because "darwin" contains "win".
        "mac" in os || "darwin" in os ->
            File(File(userHome, "Library/Application Support"), APP_DIRECTORY_NAME)
        "win" in os -> {
            val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
            File(appData?.let { File(it) } ?: File(userHome, "AppData/Roaming"), APP_DIRECTORY_NAME)
        }
        else -> {
            val xdgDataHome = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
            File(xdgDataHome?.let { File(it) } ?: File(userHome, ".local/share"), APP_DIRECTORY_NAME_XDG)
        }
    }
}
