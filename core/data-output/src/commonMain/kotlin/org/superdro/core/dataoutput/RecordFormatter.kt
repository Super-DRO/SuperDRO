package org.superdro.core.dataoutput

import org.superdro.core.model.AngleUnit
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputField
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.TimestampFormat

/**
 * Makes the records of the data output. Each record is one line of UTF-8 text with one line
 * feed (0x0A) at the end. It
 * has no header, no length, and no checksum. The time stamp comes first, then `sequence` (JSON
 * only), then the enabled fields in the order of the field list.
 *
 * Delimited: `[timestamp DELIM] value *(DELIM value) LF`. With the labels on, the time stamp is
 * `timestamp=<time>` and each value is `<label>=<value>`. A field with no value gives an empty
 * text. The configuration refuses a label with `"`, `\`, `=`, the delimiter, or a control
 * character.
 *
 * JSON: `{"timestamp":<time>,"sequence":<n>,"<label>":<number or null>,...}` with no spaces. The
 * key of a field is its label, with the JSON escapes. With the angle unit DMS, the JSON
 * values are decimal degrees.
 *
 * Use [create]. It refuses a configuration with problems ([DataOutputValidation.problems]).
 */
class RecordFormatter private constructor(
    private val config: DataOutputConfig,
    /** The format of the records. MQTT uses [DataOutputFormat.Json] for each layout. */
    val format: DataOutputFormat,
) {

    /** The enabled fields, in the order of the field list. */
    val fields: List<DataOutputField> = config.fields.filter { field -> field.enabled }

    /**
     * Gives the value text of each enabled field, in order, with null for a field with no value.
     * The "on change" check of the cadence compares these texts.
     */
    fun valueTexts(values: OutputValues): List<String?> = fields.map { field -> valueText(field, values[field.id]) }

    /** Gives the time stamp text of [epochMs], with no quotes. */
    fun timestampText(epochMs: Long): String = OutputTime.text(epochMs, config.timestampFormat)

    /**
     * Gives the record of [values], with the line feed at the end.
     *
     * @param timestampEpochMs The time stamp: the time of the last value update.
     * @param sequence The record number since the start (JSON only).
     */
    fun record(values: OutputValues, timestampEpochMs: Long, sequence: Long): String =
        record(valueTexts(values), timestampEpochMs, sequence)

    /** Gives the record of the value texts [texts] (from [valueTexts]), with the line feed at the end. */
    fun record(texts: List<String?>, timestampEpochMs: Long, sequence: Long): String {
        require(texts.size == fields.size) { "There are ${texts.size} texts for ${fields.size} fields." }
        return when (format) {
            DataOutputFormat.Delimited -> delimited(texts, timestampEpochMs)
            DataOutputFormat.Json -> json(texts, timestampEpochMs, sequence)
        } + RECORD_END
    }

    private fun delimited(texts: List<String?>, timestampEpochMs: Long): String {
        val parts = ArrayList<String>(texts.size + 1)
        if (config.timestampEnabled) {
            val time = timestampText(timestampEpochMs)
            parts += if (config.labels) "$TIMESTAMP_KEY=$time" else time
        }
        fields.forEachIndexed { index, field ->
            val text = texts[index].orEmpty()
            parts += if (config.labels) "${field.label}=$text" else text
        }
        return parts.joinToString(config.delimiter.text)
    }

    private fun json(texts: List<String?>, timestampEpochMs: Long, sequence: Long): String = buildString {
        append('{')
        if (config.timestampEnabled) {
            append('"').append(TIMESTAMP_KEY).append("\":")
            val time = timestampText(timestampEpochMs)
            when (config.timestampFormat) {
                TimestampFormat.Iso8601 -> append('"').append(time).append('"')
                TimestampFormat.EpochMs -> append(time)
            }
            append(',')
        }
        append('"').append(SEQUENCE_KEY).append("\":").append(sequence)
        fields.forEachIndexed { index, field ->
            append(",\"").append(jsonEscape(field.label)).append("\":").append(texts[index] ?: "null")
        }
        append('}')
    }

    private fun valueText(field: DataOutputField, value: Double?): String? {
        if (value == null || !value.isFinite()) return null
        val precision = config.precision
        return when (field.id.kind) {
            FieldKind.Distance, FieldKind.DistanceRate ->
                FixedPoint.format(OutputUnits.distance(value, config.distanceUnit), precision)
            FieldKind.Angle, FieldKind.AngleRate -> angleText(value, precision)
            FieldKind.SurfaceSpeed -> FixedPoint.format(OutputUnits.surfaceSpeed(value, config.distanceUnit), precision)
            FieldKind.Rpm, FieldKind.Flag, FieldKind.Direction, FieldKind.EpochMs, FieldKind.Count ->
                FixedPoint.format(value, 0)
        }
    }

    private fun angleText(arcSeconds: Double, precision: Int): String =
        if (config.angleUnit == AngleUnit.DegreesMinutesSeconds && format == DataOutputFormat.Delimited) {
            OutputUnits.dms(arcSeconds, precision)
        } else {
            FixedPoint.format(OutputUnits.angle(arcSeconds, config.angleUnit), precision)
        }

    companion object {
        /** The end of each record: one line feed (0x0A). */
        const val RECORD_END: Char = '\n'

        /** The key of the time stamp. */
        const val TIMESTAMP_KEY: String = "timestamp"

        /** The key of the record number (JSON only). */
        const val SEQUENCE_KEY: String = "sequence"

        /**
         * Makes the formatter of [config] for [format].
         *
         * @throws InvalidConfigException when [config] has problems.
         */
        fun create(config: DataOutputConfig, format: DataOutputFormat = config.format): RecordFormatter {
            val problems = DataOutputValidation.problems(config)
            if (problems.isNotEmpty()) throw InvalidConfigException(problems)
            return RecordFormatter(config, format)
        }

        /**
         * Gives [text] with the JSON escapes (RFC 8259, section 7): `\"`, `\\`, `\b`, `\f`, `\n`,
         * `\r`, `\t`, and `\u00XX` for the other control characters. The solidus `/` stays.
         */
        fun jsonEscape(text: String): String = buildString(text.length + 8) {
            text.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') {
                        append("\\u00")
                        append(HEX[char.code shr 4])
                        append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
                }
            }
        }

        private const val HEX = "0123456789abcdef"
    }
}
