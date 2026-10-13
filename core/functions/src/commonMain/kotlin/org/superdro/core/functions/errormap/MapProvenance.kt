package org.superdro.core.functions.errormap

/**
 * The provenance of a map from its `key: value` comments. The keys are not case-sensitive.
 *
 * @property generatedBy The source, for example "SuperDRO Error Map Wizard". A map with this key
 *   opens read-only in the editor. An imported TouchDRO map file also has this key.
 * @property generatedOn The date text `yyyy-MM-dd HH:mm:ss`.
 * @property passes The number of passes.
 * @property spreadBeforeCounts P95 − P5 of the error before the map, in counts.
 * @property spreadAfterCounts P95 − P5 of the held-out error after the map, in counts.
 * @property notes The other comment lines, without the `#`.
 */
data class MapProvenance(
    val generatedBy: String? = null,
    val generatedOn: String? = null,
    val passes: Int? = null,
    val spreadBeforeCounts: Double? = null,
    val spreadAfterCounts: Double? = null,
    val notes: List<String> = emptyList(),
) {
    /** True when the map came from a wizard. The editor then opens it read-only. */
    val readOnly: Boolean get() = generatedBy != null

    /** Gives the comment lines of this provenance, the known keys first, then the notes. */
    fun toComments(): List<String> = buildList {
        generatedBy?.let { add("# $KEY_GENERATED_BY: $it") }
        generatedOn?.let { add("# $KEY_GENERATED_ON: $it") }
        passes?.let { add("# $KEY_PASSES: $it") }
        spreadBeforeCounts?.let { add("# $KEY_SPREAD_BEFORE: $it") }
        spreadAfterCounts?.let { add("# $KEY_SPREAD_AFTER: $it") }
        notes.forEach { add("# $it") }
    }

    companion object {
        /** The value of `generated-by` that the SuperDRO wizard writes. */
        const val SUPERDRO_WIZARD: String = "SuperDRO Error Map Wizard"

        const val KEY_GENERATED_BY: String = "generated-by"
        const val KEY_GENERATED_ON: String = "generated-on"
        const val KEY_PASSES: String = "passes"
        const val KEY_SPREAD_BEFORE: String = "spread-before-counts"
        const val KEY_SPREAD_AFTER: String = "spread-after-counts"

        /**
         * Reads the provenance from [comments]: the comment lines with or without the `#`, for
         * example [EncoderProfile.errorMapComments][org.superdro.core.model.EncoderProfile.errorMapComments].
         * The function removes one `#` from each line, then it reads the text as
         * [fromCommentTexts] does.
         */
        fun fromComments(comments: List<String>): MapProvenance =
            fromCommentTexts(comments.map { comment -> comment.trim().removePrefix("#").trim() })

        /**
         * Reads the provenance from [texts]: the comment texts with no `#`, as
         * [MapParseResult.comments][org.superdro.core.readout.encoder.MapParseResult.comments]
         * gives them. A known key with a value that is not a number of its type becomes a note.
         * The lines of an export header ([MapExport.isHeaderComment]) are not notes, so an export
         * and an import again do not add a second header.
         */
        fun fromCommentTexts(texts: List<String>): MapProvenance {
            var by: String? = null
            var on: String? = null
            var passes: Int? = null
            var before: Double? = null
            var after: Double? = null
            val notes = ArrayList<String>()
            for (text in texts) {
                val body = text.trim()
                if (MapExport.isHeaderComment(body)) continue
                val colon = body.indexOf(':')
                val key = if (colon > 0) body.substring(0, colon).trim().lowercase() else ""
                val value = if (colon > 0) body.substring(colon + 1).trim() else ""
                val known = when (key) {
                    KEY_GENERATED_BY -> { by = value; true }
                    KEY_GENERATED_ON -> { on = value; true }
                    KEY_PASSES -> value.toIntOrNull()?.let { passes = it; true } ?: false
                    KEY_SPREAD_BEFORE -> value.toDoubleOrNull()?.let { before = it; true } ?: false
                    KEY_SPREAD_AFTER -> value.toDoubleOrNull()?.let { after = it; true } ?: false
                    else -> false
                }
                if (!known && body.isNotEmpty()) notes.add(body)
            }
            return MapProvenance(by, on, passes, before, after, notes)
        }
    }
}
