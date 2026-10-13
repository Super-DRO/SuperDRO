package org.superdro.core.store.files

/**
 * The content of the report screen: a title, a summary, and sections. The import of a
 * file and the error map report use it.
 */
data class Report(
    val title: String,
    val summary: String,
    val sections: List<ReportSection>,
) {
    /**
     * Gives the text export of the report:
     *
     * ```text
     * <title>
     * <one "=" for each character of the title>
     *
     * <summary>
     *
     * <56 "-">
     * <section title>
     *
     * <section text>
     * ```
     *
     * The text is trimmed and has one LF at the end.
     */
    fun toText(): String = buildString {
        append(title).append('\n')
        append("=".repeat(title.length)).append('\n')
        append('\n')
        append(summary).append('\n')
        sections.forEach { section ->
            append('\n')
            append(SECTION_LINE).append('\n')
            append(section.title).append('\n')
            append('\n')
            append(section.text).append('\n')
        }
    }.trim() + "\n"

    /**
     * Gives the text export as a file: `superdro-<kind>-<name>-<yyyyMMdd_HHmmss>.txt`, for example
     * `superdro-import_report-Bridgeport-20261011_143005.txt`.
     */
    fun exportFile(kind: String, name: String, context: ExportContext): ExportFile =
        ExportFile(ExportFileNames.name(listOf(kind, name), context.localTime, "txt"), ExportFile.MIME_TEXT, toText())

    companion object {
        /** The line of 56 `-` before each section. */
        val SECTION_LINE: String = "-".repeat(56)

        /** The kind of the file name of an import report. */
        const val IMPORT_REPORT_KIND: String = "import-report"
    }
}

/** One section of a [Report]. */
data class ReportSection(val title: String, val text: String)

/**
 * The report of one import: one report of each item that the import did not map, the
 * values that got the default, and where the probe values go. The UI shows it on the
 * report screen.
 *
 * The items are texts, because some items have a row label and a message. [render] gives the
 * [Report] with the texts of a locale.
 */
data class ImportReport(
    val imported: Int,
    val notImported: List<ImportProblem>,
    val defaults: List<ImportProblem>,
    val probe: List<ImportMessage>,
    val warnings: List<ImportProblem>,
    /** The title of the section [notImported]: the items of a settings file, or the rows of a CSV file. */
    val notImportedTitle: MessageKey = MessageKey.ReportNotMapped,
) {
    /** True when the report has an item. */
    val hasItems: Boolean
        get() = notImported.isNotEmpty() || defaults.isNotEmpty() || probe.isNotEmpty() || warnings.isNotEmpty()

    /** Gives the [Report] with the texts of [texts]. An empty section does not show. */
    fun render(texts: ImportTexts = ImportTexts.English, title: ImportMessage = ImportMessage(MessageKey.ReportTitle)): Report {
        val sections = buildList {
            if (notImported.isNotEmpty()) {
                add(ReportSection(texts.text(ImportMessage(notImportedTitle)), notImported.joinToString("\n") { it.describe(texts) }))
            }
            if (defaults.isNotEmpty()) {
                add(ReportSection(texts.text(ImportMessage(MessageKey.ReportDefaults)), defaults.joinToString("\n") { it.describe(texts) }))
            }
            if (probe.isNotEmpty()) {
                add(ReportSection(texts.text(ImportMessage(MessageKey.ReportProbe)), probe.joinToString("\n") { texts.text(it) }))
            }
            if (warnings.isNotEmpty()) {
                add(ReportSection(texts.text(ImportMessage(MessageKey.ReportWarnings)), warnings.joinToString("\n") { it.describe(texts) }))
            }
        }
        val warningCount = defaults.size + warnings.size
        return Report(
            title = texts.text(title),
            summary = texts.text(ImportMessage(MessageKey.ReportSummary, imported, notImported.size, warningCount)),
            sections = sections,
        )
    }
}
