package org.superdro.core.connection

import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.IOException
import java.io.Writer
import org.superdro.core.model.AdapterAddress

/**
 * The [StreamRecorder] of the apps: one text file for each recorded link
 * in the folder [directory], in the format of [RecordedStream]. The Android app gives
 * `<files>/streams`, and the desktop app gives `<application data directory>/streams`. The user
 * exports a file through the share sheet or the file picker.
 *
 * The class is in the source set `jvmAndAndroidMain`: the two JVM targets share it, because it
 * uses `java.io.File`.
 *
 * A write failure does not stop the session: the recorder logs it and writes no more lines to that
 * file.
 *
 * The recorder finds the folder at the first [open]. On Android, `Context.getFilesDir` reads the
 * disk, and the DI graph can make the recorder on the main thread.
 *
 * @param directory Gives the folder of the files. The recorder calls it one time, at the first
 *   [open].
 * @param nowMs Gives the wall clock time for the file name.
 */
class FileStreamRecorder(
    directory: () -> File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : StreamRecorder {

    /** Makes a recorder with the folder [directory]. */
    constructor(directory: File, nowMs: () -> Long = System::currentTimeMillis) : this({ directory }, nowMs)

    private val directory: File by lazy(directory)

    override fun open(device: DeviceId, address: AdapterAddress, link: Int): RecordSink? {
        val file = File(directory, fileName(device, link, nowMs()))
        val writer: Writer = try {
            directory.mkdirs()
            file.bufferedWriter()
        } catch (error: IOException) {
            log.w(error) { "No record file for $device" }
            return null
        }
        log.i { "Record of $device (${address.kind}, link $link) to ${file.absolutePath}" }
        var failed = false
        return LineRecordSink(
            writeLine = { line ->
                if (!failed) {
                    try {
                        writer.write(line)
                        writer.write("\n")
                    } catch (error: IOException) {
                        failed = true
                        log.w(error) { "The record of $device stopped" }
                    }
                }
            },
            onClose = {
                try {
                    writer.close()
                } catch (error: IOException) {
                    log.w(error) { "The record file of $device did not close" }
                }
            },
        )
    }

    companion object {
        /** The name of the folder of the files in the data folder of the app. */
        const val FOLDER: String = "streams"

        private val log = Arbor.tag("StreamRecorder")
        private val unsafe = Regex("[^A-Za-z0-9._-]")

        /** Gives the file name `<device>-<ms>-<link>.txt`. Each character that is not safe in a file name becomes `_`. */
        fun fileName(device: DeviceId, link: Int, nowMs: Long): String = "${device.value.replace(unsafe, "_")}-$nowMs-$link.txt"
    }
}
