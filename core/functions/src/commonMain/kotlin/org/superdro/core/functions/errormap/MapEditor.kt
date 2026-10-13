package org.superdro.core.functions.errormap

import kotlin.math.abs
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.MapText

/**
 * A problem of a map import. The UI shows the
 * English text of the KDoc of each code.
 */
sealed interface MapImportProblem {

    /** "Choose a .csv file." The display name of the file does not end with `.csv`. */
    data object ChooseCsvFile : MapImportProblem

    /** "This does not look like a position, correction CSV." The text looks like binary data. */
    data object LooksBinary : MapImportProblem

    /**
     * The text has more than 20000 points (the key `map_too_many_points`).
     * SuperDRO text: "The map has more than 20000 points."
     */
    data object TooManyPoints : MapImportProblem

    /** "No valid points found to import." The text has no points and no malformed lines. */
    data object NoValidPoints : MapImportProblem

    /**
     * The text has no points, or more malformed lines than points (the key `map_not_csv`). SuperDRO
     * text: "This is not a position, correction CSV file."
     */
    data object NotCsv : MapImportProblem

    /** "Line %1$d is not a valid position, correction pair (two whole numbers)." */
    data class MalformedLine(val line: Int) : MapImportProblem

    /**
     * "Positions and corrections are raw encoder counts — whole numbers, not inches or
     * millimeters." It comes after the other problems when a malformed line has a decimal value.
     */
    data object DecimalValues : MapImportProblem

    /** A problem of the validator ([MapValidation]). */
    data class Invalid(val problem: MapProblem) : MapImportProblem
}

/**
 * The data of the question "Replace Current Points?" (step 7 of [MapEditor.import]).
 *
 * @property currentCount The number of rows in the table now.
 * @property newCount The number of points of the import.
 * @property firstPosition The smallest position of the import.
 * @property lastPosition The largest position of the import.
 */
data class ReplaceQuestion(
    val currentCount: Int,
    val newCount: Int,
    val firstPosition: Long,
    val lastPosition: Long,
)

/**
 * The result of a map import.
 *
 * @property accepted True when the import has no problem. Then [points] can replace the rows.
 * @property points The points sorted by position, after the absolute-value conversion.
 * @property problems The problems in the order of the import steps.
 * @property convertedFromAbsolute True when the editor changed true positions to corrections.
 *   The UI then shows the notice `error_map_converted_notice` (SuperDRO text: "The file held true
 *   positions. Each value is now a correction: value − position.").
 * @property provenance The provenance from the comments.
 * @property readOnly True when the text has a `generated-by` comment.
 * @property replaceQuestion The question "Replace Current Points?" when the table has rows.
 */
data class MapImportResult(
    val accepted: Boolean,
    val points: List<MapPoint>,
    val problems: List<MapImportProblem>,
    val convertedFromAbsolute: Boolean,
    val provenance: MapProvenance,
    val readOnly: Boolean,
    val replaceQuestion: ReplaceQuestion?,
)

/** The result of "Save" in the map editor. */
sealed interface MapSaveResult {

    /**
     * The map is valid.
     *
     * @property points The rows sorted by position.
     * @property text The map text: the provenance comments, then one `position,value` line for
     *   each row.
     */
    data class Saved(val points: List<MapPoint>, val text: String) : MapSaveResult

    /** The table has no rows. "Save" clears the map after a confirm dialog. */
    data object Cleared : MapSaveResult

    /**
     * "This map could not be saved because it is not a valid map." The validator found
     * duplicates, reversals, dead bands, or bad backlash values, or the map has more than 20000
     * points ([tooManyPoints]).
     */
    data class Refused(val problems: List<MapProblem>, val tooManyPoints: Boolean = false) : MapSaveResult
}

/**
 * The summary tab of the map editor.
 *
 * @property firstPosition The first position of the mapped span, or null with no point.
 * @property lastPosition The last position of the mapped span.
 * @property intervalCount The number of intervals (points − 1). The Encoder Settings summary
 *   "%1$d intervals (%2$d – %3$d)" uses it.
 * @property typicalSpacing "Typical spacing": the median distance between neighbor positions,
 *   rounded.
 * @property largestCorrection "Largest correction": `max(maxValue, −minValue)`, shown with ±.
 * @property source The provenance ("Built by …", "Built on …", "From N passes", other notes).
 * @property errorBeforeHalf "Error": `±before/2` from `spread-before-counts`.
 * @property errorAfterHalf "Error": `±after/2` from `spread-after-counts`.
 */
data class MapSummary(
    val firstPosition: Long?,
    val lastPosition: Long?,
    val intervalCount: Int,
    val typicalSpacing: Long?,
    val largestCorrection: Long?,
    val source: MapProvenance,
    val errorBeforeHalf: Double?,
    val errorAfterHalf: Double?,
)

/**
 * The rules of the map editor: import, paste,
 * clear, save, and the summary. The editor table and the curve are in `core:ui`.
 */
object MapEditor {

    /** The number of characters that the binary check examines. */
    const val BINARY_CHECK_LENGTH: Int = 2000

    /**
     * Checks the display name of a picked file. A known name must end with `.csv` (not
     * case-sensitive). An unknown name (null) passes. A failure gives
     * [MapImportProblem.ChooseCsvFile].
     */
    fun isCsvFileName(displayName: String?): Boolean =
        displayName == null || displayName.lowercase().endsWith(".csv")

    /**
     * Gives true when [text] looks like binary data: it has a NUL character, or more than 1/20 of
     * the first [BINARY_CHECK_LENGTH] characters are control characters (not CR, LF, or TAB) or
     * U+FFFD.
     */
    fun looksBinary(text: String): Boolean {
        if (text.indexOf('\u0000') >= 0) return true
        val sample = text.take(BINARY_CHECK_LENGTH)
        if (sample.isEmpty()) return false
        val bad = sample.count { c ->
            c == '�' || (c.isISOControl() && c != '\r' && c != '\n' && c != '\t')
        }
        return bad * 20 > sample.length
    }

    /**
     * Imports a map text (a file or the clipboard: "Paste" uses the same steps). The steps:
     *
     * 1. A text that looks like binary data gives [MapImportProblem.LooksBinary].
     * 2. Parse ([MapText]). More than 20000 points gives [MapImportProblem.TooManyPoints].
     * 3. No points and no malformed lines gives [MapImportProblem.NoValidPoints]. No points, or
     *    more malformed lines than points, gives [MapImportProblem.NotCsv]. Else each malformed
     *    line gives [MapImportProblem.MalformedLine].
     * 4. When problems exist and a malformed line has a decimal value, add
     *    [MapImportProblem.DecimalValues].
     * 5. Error map only: with 2 or more points, if `max|value − position| × 4 <
     *    max(1, maxPosition − minPosition)`, the file holds true positions. Each value changes to
     *    `value − position`.
     * 6. Validate ([MapValidation]).
     * 7. When the table has rows ([currentRowCount] > 0), give the data of "Replace Current
     *    Points?".
     * 8. A `generated-by` comment makes the map read-only.
     *
     * SuperDRO refuses the import when a problem exists ([MapImportResult.accepted] is false).
     *
     * @param countsPerScrewRev The counts of one screw turn for the backlash check, or null.
     */
    fun import(
        text: String,
        kind: MapKind,
        currentRowCount: Int = 0,
        countsPerScrewRev: Long? = null,
    ): MapImportResult {
        if (looksBinary(text)) return refused(listOf(MapImportProblem.LooksBinary))
        val parsed = MapText.parse(text)
        val provenance = MapProvenance.fromCommentTexts(parsed.comments)
        if (parsed.exceededLimit) return refused(listOf(MapImportProblem.TooManyPoints), provenance)
        val problems = ArrayList<MapImportProblem>()
        val points = parsed.points
        val malformed = parsed.malformedLines
        when {
            points.isEmpty() && malformed.isEmpty() -> problems.add(MapImportProblem.NoValidPoints)
            points.isEmpty() || malformed.size > points.size -> problems.add(MapImportProblem.NotCsv)
            else -> malformed.forEach { problems.add(MapImportProblem.MalformedLine(it)) }
        }
        if (problems.isNotEmpty() && parsed.sawDecimalValues) problems.add(MapImportProblem.DecimalValues)
        if (problems.isNotEmpty()) return refused(problems, provenance)

        var converted = false
        var result = points
        if (kind == MapKind.Error && holdsTruePositions(points)) {
            result = points.map { MapPoint(it.position, it.value - it.position) }
            converted = true
        }
        MapValidation.validate(kind, result, countsPerScrewRev).forEach { problems.add(MapImportProblem.Invalid(it)) }
        val sorted = result.sortedBy { it.position }
        val question = if (currentRowCount > 0 && sorted.isNotEmpty()) {
            ReplaceQuestion(currentRowCount, sorted.size, sorted.first().position, sorted.last().position)
        } else {
            null
        }
        return MapImportResult(
            accepted = problems.isEmpty(),
            points = sorted,
            problems = problems,
            convertedFromAbsolute = converted,
            provenance = provenance,
            readOnly = provenance.readOnly,
            replaceQuestion = question,
        )
    }

    /**
     * Gives true when an error map holds true positions in place of corrections (step 5 of [import]):
     * 2 or more points and `max|value − position| × 4 < max(1, maxPosition − minPosition)`.
     */
    fun holdsTruePositions(points: List<MapPoint>): Boolean {
        if (points.size < 2) return false
        val maxDifference = points.maxOf { abs(it.value - it.position) }
        val span = points.maxOf { it.position } - points.minOf { it.position }
        return maxDifference * 4 < maxOf(1L, span)
    }

    /**
     * Saves the rows of the editor. An empty table gives [MapSaveResult.Cleared]. Else the rows
     * must pass the validator of [kind]: duplicates, reversals, and dead bands (error map), or
     * duplicates, negative values, and values of one screw turn or more (backlash map) give
     * [MapSaveResult.Refused]. A valid map gives the rows sorted by position and the map text
     * with the provenance comments first.
     */
    fun save(
        kind: MapKind,
        rows: List<MapPoint>,
        provenance: MapProvenance = MapProvenance(),
        countsPerScrewRev: Long? = null,
    ): MapSaveResult {
        if (rows.isEmpty()) return MapSaveResult.Cleared
        if (rows.size > MapText.MAX_POINTS) return MapSaveResult.Refused(emptyList(), tooManyPoints = true)
        val problems = MapValidation.validate(kind, rows, countsPerScrewRev)
        if (problems.isNotEmpty()) return MapSaveResult.Refused(problems)
        val sorted = rows.sortedBy { it.position }
        return MapSaveResult.Saved(sorted, mapText(sorted, provenance))
    }

    /** "Clear": the table has no rows. "Save" then gives [MapSaveResult.Cleared]. */
    fun clear(): List<MapPoint> = emptyList()

    /** Gives the map text: the provenance comments, then `position,value` lines, LF at each end. */
    fun mapText(points: List<MapPoint>, provenance: MapProvenance = MapProvenance()): String = buildString {
        provenance.toComments().forEach { append(it).append('\n') }
        points.sortedBy { it.position }.forEach { append(it.position).append(',').append(it.value).append('\n') }
    }

    /** Gives the summary tab of [points] with [provenance]. */
    fun summary(points: List<MapPoint>, provenance: MapProvenance = MapProvenance()): MapSummary {
        val sorted = points.sortedBy { it.position }
        val gaps = (1 until sorted.size).map { (sorted[it].position - sorted[it - 1].position).toDouble() }
        val spacing = Stats.median(gaps)?.let { Stats.roundHalfUp(it) }
        val largest = if (sorted.isEmpty()) null else maxOf(sorted.maxOf { it.value }, -sorted.minOf { it.value })
        return MapSummary(
            firstPosition = sorted.firstOrNull()?.position,
            lastPosition = sorted.lastOrNull()?.position,
            intervalCount = maxOf(0, sorted.size - 1),
            typicalSpacing = spacing,
            largestCorrection = largest,
            source = provenance,
            errorBeforeHalf = provenance.spreadBeforeCounts?.let { it / 2.0 },
            errorAfterHalf = provenance.spreadAfterCounts?.let { it / 2.0 },
        )
    }

    private fun refused(problems: List<MapImportProblem>, provenance: MapProvenance = MapProvenance()) =
        MapImportResult(
            accepted = false,
            points = emptyList(),
            problems = problems,
            convertedFromAbsolute = false,
            provenance = provenance,
            readOnly = provenance.readOnly,
            replaceQuestion = null,
        )
}

/** The export of a map from the editor, with its header lines. */
object MapExport {

    private val errorHeaderBody = listOf(
        "position   = raw encoder count, measured from the machine datum",
        "correction = signed delta, in encoder counts",
        "             corrected position = position + correction",
        "NOTE: valid only while the axis is referenced to the same datum this map",
        "      was made with. Re-reference before applying it.",
    )

    private val backlashHeaderBody = listOf(
        "position = raw encoder count, measured from the machine datum",
        "backlash = lost motion on a direction reversal at that position,",
        "           in encoder counts. Never negative; always less than one",
        "           turn of the leadscrew.",
        "NOTE: valid only while the axis is referenced to the same datum this map",
        "      was made with. Re-reference before applying it.",
    )

    private val titles = listOf(
        "SuperDRO error correction map",
        "SuperDRO backlash map",
        "TouchDRO error correction map",
        "TouchDRO backlash map",
    )

    private val fixedBodies: Set<String> =
        (errorHeaderBody + backlashHeaderBody + titles).map { collapse(it) }.toSet()

    /**
     * Gives the file name `<error-correction-map|backlash-map>-<adapter>-<INPUT>-<dateTime>.csv`.
     *
     * @param adapterName The adapter name. Each run of characters that are not `A-Z a-z 0-9`
     *   becomes `_`, then the `_` at the two ends go. An empty result gives `adapter`.
     * @param input The input letter. The name uses the uppercase letter.
     * @param dateTime The date and time text `yyyyMMdd_HHmmss`.
     */
    fun fileName(kind: MapKind, adapterName: String, input: Char, dateTime: String): String {
        val prefix = when (kind) {
            MapKind.Error -> "error-correction-map"
            MapKind.Backlash -> "backlash-map"
        }
        val adapter = cleanFileNamePart(adapterName).ifEmpty { "adapter" }
        return "$prefix-$adapter-${input.uppercaseChar()}-$dateTime.csv"
    }

    /**
     * Gives the export header lines of [kind] (with the name SuperDRO).
     * The last line is the column header `position,correction` or `position,backlash`.
     *
     * @param exportedOn The date and time text `yyyy-MM-dd HH:mm:ss`.
     */
    fun headerLines(kind: MapKind, adapterName: String, input: Char, exportedOn: String): List<String> {
        val title = if (kind == MapKind.Error) titles[0] else titles[1]
        val body = if (kind == MapKind.Error) errorHeaderBody else backlashHeaderBody
        val column = if (kind == MapKind.Error) "position,correction" else "position,backlash"
        return buildList {
            add("# $title")
            add("# adapter: $adapterName  |  input: ${input.uppercaseChar()}  |  exported: $exportedOn")
            body.forEach { add("# $it") }
            add(column)
        }
    }

    /**
     * Gives the export text: the header lines, then the provenance comments, then one
     * `position,value` line for each point, sorted by position. Each line ends with LF.
     */
    fun text(
        kind: MapKind,
        points: List<MapPoint>,
        adapterName: String,
        input: Char,
        exportedOn: String,
        provenance: MapProvenance = MapProvenance(),
    ): String = buildString {
        val header = headerLines(kind, adapterName, input, exportedOn)
        header.dropLast(1).forEach { append(it).append('\n') }
        provenance.toComments().forEach { append(it).append('\n') }
        append(header.last()).append('\n')
        points.sortedBy { it.position }.forEach { append(it.position).append(',').append(it.value).append('\n') }
    }

    /**
     * Gives true when [body] (a comment without the `#`) is a line of an export header of
     * SuperDRO or of a TouchDRO map file. The provenance reader does not keep these lines as notes.
     */
    fun isHeaderComment(body: String): Boolean {
        val text = collapse(body)
        if (text in fixedBodies) return true
        return text.startsWith("adapter:") && text.contains("input:")
    }

    /**
     * Cleans one part of a file name: each run of characters that are not `A-Z a-z 0-9` becomes
     * `_`, and the `_` at the two ends go.
     */
    fun cleanFileNamePart(part: String): String =
        part.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

    private fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
