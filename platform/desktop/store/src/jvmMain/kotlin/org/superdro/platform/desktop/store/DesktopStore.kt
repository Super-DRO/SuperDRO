package org.superdro.platform.desktop.store

import app.cash.sqldelight.db.SqlDriver
import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.util.SecretStore

/**
 * The files of the desktop app: the application data directory of the user, the
 * [StoreIOProviderJvm] of that directory, the database file, and the secret store.
 *
 * The default directory is the directory of each operating system that `StoreIOProviderJvm` uses:
 * - Linux: `$XDG_DATA_HOME/superdro`, else `~/.local/share/superdro`
 * - macOS: `~/Library/Application Support/SuperDRO`
 * - Windows: `%APPDATA%\SuperDRO`
 *
 * The argument `--data-dir` of the app gives a different directory, for example for a test. The
 * default directory, and a directory of the argument that is not there yet, get the POSIX
 * permissions `rwx------` where the file system has them. Then a different user of the computer
 * cannot read the files.
 *
 * @param dataDirectory The directory, or null for the default directory.
 */
class DesktopStore(dataDirectory: File? = null) {

    // The default directory belongs only to SuperDRO. A directory of the argument gets the
    // permissions only when it is not there yet. StoreIOProviderJvm makes the directory at the
    // first access, so this check comes first.
    private val restrict: Boolean = dataDirectory == null || !dataDirectory.exists()

    /** The file access of the stores. Each relative store name is in [directory]. */
    val storeIO: StoreIOProviderJvm = if (dataDirectory != null) StoreIOProviderJvm(dataDirectory.absoluteFile) else StoreIOProviderJvm()

    /** The application data directory. The constructor makes it. */
    val directory: File = run {
        val databasePath = File(storeIO.pathFor(DATABASE_NAME)).absoluteFile
        val folder = requireNotNull(databasePath.parentFile) { "The database path $databasePath has no folder." }
        if (!folder.isDirectory && !folder.mkdirs()) log.e { "Cannot make the application data directory ${folder.absolutePath}" }
        if (restrict) DesktopFiles.restrictToOwner(folder)
        folder
    }

    /** The database file. */
    val databaseFile: File get() = File(directory, DATABASE_NAME)

    /** Opens the database. Open one driver for the process. */
    fun openDriver(): SqlDriver = DesktopSqlDriverFactory(databaseFile).create()

    /** Gives the secret store of this directory. Read [DesktopSecretStore] for its limits. */
    fun secretStore(): SecretStore = DesktopSecretStore(storeIO, File(directory, DesktopSecretStore.KEY_FILE_NAME))

    companion object {
        /** The file name of the database. The Android app uses the same name. */
        const val DATABASE_NAME: String = "superdro.db"

        private val log = Arbor.tag("DesktopStore")
    }
}

/** The file permission helpers of the desktop store. */
internal object DesktopFiles {

    private val log = Arbor.tag("DesktopFiles")

    /** `rwx------`: only the owner can read, write, and open the directory. */
    val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

    /** `rw-------`: only the owner can read and write the file. */
    val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /** True when the default file system has POSIX permissions (Linux and macOS, not Windows). */
    val posix: Boolean get() = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    /** Gives [directory] the permissions `rwx------` on a POSIX file system. A failure goes to the log. */
    fun restrictToOwner(directory: File) {
        if (!posix) return
        runCatching { Files.setPosixFilePermissions(directory.toPath(), OWNER_ONLY_DIRECTORY) }
            .onFailure { error -> log.w(error) { "Cannot set the permissions of ${directory.absolutePath}" } }
    }

    /**
     * Writes [bytes] to [target] in one atomic step: first a temporary file in the same folder with the
     * permissions `rw-------` on a POSIX file system, then a move that replaces [target]. The bytes
     * are on the disk before the move.
     */
    fun writeOwnerOnly(target: File, bytes: ByteArray) {
        val folder = target.absoluteFile.parentFile
        folder.mkdirs()
        val temp = if (posix) {
            Files.createTempFile(folder.toPath(), target.name, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
        } else {
            Files.createTempFile(folder.toPath(), target.name, ".tmp")
        }
        try {
            FileOutputStream(temp.toFile()).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
