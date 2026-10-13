package org.superdro.core.store.files.csv

import org.superdro.core.model.MachineType
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.MachineNames

/**
 * The metadata block of a CSV file of format 2: the lines `# <key>: <value>` before the header.
 *
 * The import ignores an unknown key, a blank value, and the `device` value. Thus this type
 * has no device field.
 *
 * @property source The key `touchdro` or `superdro`, or null when the file has no such key.
 * @property kind The value of that key: `tools` or `sub-datums`.
 * @property metric The value of `units`: true for mm, false for inch, null when the file gives no
 *   known unit.
 * @property unknownUnits The `units` text when the import does not know it.
 */
data class CsvMetadata(
    val source: FileSource? = null,
    val kind: String? = null,
    val format: Int? = null,
    val app: String? = null,
    val exported: String? = null,
    val profileId: String? = null,
    val profile: String? = null,
    val machine: MachineType? = null,
    val metric: Boolean? = null,
    val unknownUnits: String? = null,
) {
    /** True when the file has no metadata line that the import knows. */
    val isEmpty: Boolean
        get() = this == CsvMetadata()

    companion object {
        const val KIND_TOOLS: String = "tools"
        const val KIND_SUB_DATUMS: String = "sub-datums"

        /** The format of the CSV files with a metadata block. */
        const val FORMAT: Int = 2

        private val MM_WORDS = setOf("mm", "metric", "millimeter", "millimeters", "millimetre", "millimetres")
        private val INCH_WORDS = setOf("in", "inch", "inches", "imperial", "\"")

        /**
         * Reads the unit word [text] in any case: true for mm, false for inch, null for a word
         * that is not known.
         */
        fun parseUnits(text: String): Boolean? = when (text.trim().lowercase()) {
            in MM_WORDS -> true
            in INCH_WORDS -> false
            else -> null
        }

        /**
         * Reads the metadata lines. Each line starts with `#`. The key is the text before the
         * first `:`, in lowercase. The value is the text after it, trimmed.
         */
        fun parse(lines: List<String>): CsvMetadata {
            var metadata = CsvMetadata()
            lines.forEach { line ->
                val body = line.trim().removePrefix("#").trim()
                val colon = body.indexOf(':')
                if (colon <= 0) return@forEach
                val key = body.substring(0, colon).trim().lowercase()
                val value = body.substring(colon + 1).trim()
                if (value.isEmpty()) return@forEach
                metadata = when (key) {
                    FileSource.TouchDro.key -> if (metadata.source == null) metadata.copy(source = FileSource.TouchDro, kind = value.lowercase()) else metadata
                    FileSource.SuperDro.key -> if (metadata.source == null) metadata.copy(source = FileSource.SuperDro, kind = value.lowercase()) else metadata
                    "format" -> metadata.copy(format = value.toIntOrNull())
                    "app" -> metadata.copy(app = value)
                    "exported" -> metadata.copy(exported = value)
                    "profile-id" -> metadata.copy(profileId = value)
                    "profile" -> metadata.copy(profile = value)
                    "machine" -> metadata.copy(machine = MachineNames.parse(value))
                    "units" -> {
                        val metric = parseUnits(value)
                        if (metric == null) metadata.copy(unknownUnits = value) else metadata.copy(metric = metric, unknownUnits = null)
                    }
                    // `device` and each unknown key: ignored.
                    else -> metadata
                }
            }
            return metadata
        }

        /**
         * Gives the metadata block of a SuperDRO CSV export: `superdro`
         * in place of `touchdro`, and no `device` line. A line with a blank value is left
         * out. A CR or an LF in a value changes to a space.
         *
         * @param metric The `units` line, only for the sub-datum file. Null gives no line.
         */
        fun write(
            kind: String,
            context: ExportContext,
            profileId: String,
            profileName: String,
            machineType: MachineType,
            metric: Boolean? = null,
        ): String = buildString {
            fun line(key: String, value: String) {
                val clean = value.replace('\r', ' ').replace('\n', ' ').trim()
                if (clean.isNotEmpty()) append("# ").append(key).append(": ").append(clean).append(CsvWriter.RECORD_END)
            }
            line(FileSource.SuperDro.key, kind)
            line("format", FORMAT.toString())
            line("app", context.appText)
            line("exported", context.exportedText)
            line("profile-id", profileId)
            line("profile", profileName)
            line("machine", MachineNames.name(machineType))
            if (metric != null) line("units", if (metric) "mm" else "inch")
        }
    }
}
