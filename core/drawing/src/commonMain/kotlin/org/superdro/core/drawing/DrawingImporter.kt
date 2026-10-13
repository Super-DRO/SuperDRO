package org.superdro.core.drawing

import kotlin.time.Instant
import org.superdro.core.drawing.dxf.DxfConverter
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.model.DrawingFormat
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/** The result of [DrawingImporter.import]. */
sealed interface DrawingImportResult {

    /**
     * The file gives a wireframe document with one or more entities. The "Set Up Drawing" screen
     * opens with [result].
     */
    data class Imported(val result: ConvertResult, val format: DrawingFormat) : DrawingImportResult {
        val document: WireframeDocument get() = result.wireframe!!
        val warnings: List<DrawingProblem> get() = result.warnings
    }

    /** The type test did not find a drawing: [message] is [DrawingTypeTest.NOT_A_DRAWING_MESSAGE]. */
    data class NotADrawing(val message: String = DrawingTypeTest.NOT_A_DRAWING_MESSAGE) : DrawingImportResult

    /** The file is larger than [DrawingImporter.MAX_FILE_BYTES]: [message] is [DrawingImporter.TOO_LARGE_MESSAGE]. */
    data class TooLarge(val message: String = DrawingImporter.TOO_LARGE_MESSAGE) : DrawingImportResult

    /** The drawing has no shapes: [message] is [DrawingImporter.NO_SHAPES_MESSAGE]. The details are the warnings. */
    data class NoShapes(
        val warnings: List<DrawingProblem>,
        val message: String = DrawingImporter.NO_SHAPES_MESSAGE,
    ) : DrawingImportResult

    /**
     * The file has problems: the "Drawing Not Imported" dialog shows [problems]. Nothing
     * changes.
     */
    data class NotImported(val problems: List<DrawingProblem>, val format: DrawingFormat?) : DrawingImportResult
}

/**
 * The drawing import: the size test, the type test by content, the DXF
 * converter or the wireframe reader, and the test for an empty drawing.
 * SuperDRO sends no drawing to a network service.
 */
object DrawingImporter {

    /** The size limit of a picked file: 2 162 688 bytes (2.0625 MiB). */
    const val MAX_FILE_BYTES: Int = 2_162_688

    /** The text of the size limit (string `drawing_too_large`). */
    const val TOO_LARGE_MESSAGE: String = "This drawing is too large for SuperDRO (over 2 MB)."

    /** The text of a drawing with no shapes (string `drawing_no_shapes`). */
    const val NO_SHAPES_MESSAGE: String = "SuperDRO didn't find any lines, arcs or curves to import in this drawing."

    /**
     * Imports the file content [bytes].
     *
     * @param clock The time source of the parse time limit.
     * @param now The time of `createdAt` in a converted DXF file.
     */
    fun import(
        bytes: ByteArray,
        settings: DxfImportSettings = DxfImportSettings(),
        clock: Clock = MonotonicClock(),
        now: Instant = kotlin.time.Clock.System.now(),
    ): DrawingImportResult {
        // The picked file limit never blocks a larger DXF source limit of the settings.
        if (bytes.size > maxOf(MAX_FILE_BYTES.toLong(), settings.limits.maxSourceBytes)) return DrawingImportResult.TooLarge()
        return when (DrawingTypeTest.detect(bytes)) {
            DrawingFileType.Unrecognized -> DrawingImportResult.NotADrawing()
            DrawingFileType.Wireframe -> {
                val read = WireframeReader.read(bytes)
                val result = read.result
                if (result == null) DrawingImportResult.NotImported(read.problems, DrawingFormat.Wireframe) else checked(result, DrawingFormat.Wireframe)
            }
            DrawingFileType.AsciiDxf -> dxf(bytes, settings, clock, now, DrawingFormat.AsciiDxf)
            DrawingFileType.BinaryDxf -> dxf(bytes, settings, clock, now, DrawingFormat.BinaryDxf)
        }
    }

    private fun dxf(bytes: ByteArray, settings: DxfImportSettings, clock: Clock, now: Instant, format: DrawingFormat): DrawingImportResult {
        val result = DxfConverter.convert(bytes, settings, clock, now)
        return if (result.status == ConvertStatus.Failed) {
            DrawingImportResult.NotImported(result.errors + result.warnings, format)
        } else {
            checked(result, format)
        }
    }

    private fun checked(result: ConvertResult, format: DrawingFormat): DrawingImportResult {
        val document = result.wireframe ?: return DrawingImportResult.NotImported(result.errors, format)
        return if (document.entities.isEmpty()) DrawingImportResult.NoShapes(result.warnings) else DrawingImportResult.Imported(result, format)
    }
}
