package org.superdro.app.adapterprobe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.superdro.core.adapterprobe.ProbeReport
import org.superdro.core.adapterprobe.ProbeTexts

/**
 * The share function of the probe: the report, the `.log` files, and the `.bin` files as one ZIP
 * file through the Android share sheet. The ZIP goes to the cache folder `share/`, and a
 * `FileProvider` gives its content URI. The app sends nothing over a network itself.
 */
object ProbeShare {

    /** Makes the ZIP file with the report [report] and each file of the log folder. Gives the file. */
    fun zip(context: Context, graph: ProbeGraph, report: String): File {
        val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val stamp = ProbeTexts.fileStampLocal(System.currentTimeMillis())
        val file = File(folder, "superdro-probe-$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("report.md"))
            zip.write(report.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            graph.logFolder.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { log ->
                zip.putNextEntry(ZipEntry("logs/${log.name}"))
                log.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return file
    }

    /** The report of the runs of [graph]. */
    suspend fun report(graph: ProbeGraph): String =
        ProbeReport.build(graph.records.records.value, graph.tablet, graph.pairedDevices(), System.currentTimeMillis())

    /** Opens the share sheet for [file]. */
    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private const val SHARE_FOLDER = "share"
}
