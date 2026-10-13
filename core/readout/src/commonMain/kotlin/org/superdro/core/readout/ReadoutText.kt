package org.superdro.core.readout

/**
 * The texts that `core:readout` gives to the UI.
 *
 * Each value has the name of its string resource in `core:resources`
 * (`values/strings_readout_core.xml`) and the English text of that resource. The UI shows the
 * resource. The English text is for the logs and the tests. A text with arguments uses the
 * format specifiers `%1$d` and `%1$s`.
 *
 * The texts are the texts of the settings rules, of the calibration, and of the display states.
 * The map problem texts are SuperDRO texts.
 */
enum class ReadoutText(val key: String, val english: String) {
    SamePrefix("readout_core_settings_same_prefix", "Primary and secondary inputs can't use the same prefix"),
    OneAxisRequired("readout_core_settings_one_axis_required", "At least one output axis must be enabled"),
    SampleCountRange(
        "readout_core_settings_sample_count_range",
        "Number of edge finder samples must be a number between 1 and 10 (inclusive)",
    ),

    SecondaryInputDisabled("readout_core_calibration_secondary_disabled", "Secondary Input Disabled"),
    InputPrefixMissing("readout_core_calibration_prefix_missing", "Input Prefix Missing"),
    BlockSizeRequired("readout_core_calibration_block_required", "Block Size Required"),
    BlockTooSmall("readout_core_calibration_block_too_small", "Block Too Small"),
    BlockTooSmallDetail("readout_core_calibration_block_too_small_detail", "at least 50.000 mm / 2.0000 in"),
    NoReadingReceived("readout_core_calibration_no_reading", "No Reading Received"),
    ReadingSampleTooSmall("readout_core_calibration_sample_too_small", "Reading Sample Too Small"),
    ExcessiveJitter("readout_core_calibration_jitter", "Excessive Jitter Detected"),
    ReadingsTooClose("readout_core_calibration_too_close", "Readings Too Close"),

    // The edge names of the calibration.
    EdgeLeft("readout_core_edge_left", "left"),
    EdgeRight("readout_core_edge_right", "right"),
    EdgeFront("readout_core_edge_front", "front"),
    EdgeBack("readout_core_edge_back", "back"),
    EdgeBottom("readout_core_edge_bottom", "bottom"),
    EdgeTop("readout_core_edge_top", "top"),
    EdgeFirst("readout_core_edge_first", "first"),
    EdgeSecond("readout_core_edge_second", "second"),

    // The tachometer.
    CantSetRpm("readout_core_tach_cant_set", "Can't set RPM"),

    // The display states.
    StateError("readout_core_state_error", "Error"),
    StateNoInput("readout_core_state_no_input", "No input"),

    // The problems of an error map or a backlash map.
    MapTooManyPoints("readout_core_map_too_many_points", "The map has more than %1\$d points."),
    MapMalformedLine("readout_core_map_malformed_line", "Line %1\$d is not a valid point."),
    MapNoPoints("readout_core_map_no_points", "The map has no points."),
    MapDuplicatePosition("readout_core_map_duplicate_position", "Position %1\$d occurs more than one time."),
    MapReversal(
        "readout_core_map_reversal",
        "The corrected position goes back between positions %1\$d and %2\$d.",
    ),
    MapDeadBand(
        "readout_core_map_dead_band",
        "The corrected position does not move between positions %1\$d and %2\$d.",
    ),
    MapNegativeBacklash("readout_core_map_negative_backlash", "The backlash at position %1\$d is negative (%2\$d)."),
    MapBacklashExceedsTurn(
        "readout_core_map_backlash_exceeds_turn",
        "The backlash at position %1\$d (%2\$d) is not less than one screw turn (%3\$d counts).",
    ),

    BacklashMapNotForThisEncoder(
        "readout_core_profile_backlash_map_not_allowed",
        "A backlash map is available only for a leadscrew encoder.",
    ),
}

/**
 * One text of [ReadoutText] with its arguments.
 */
data class ReadoutMessage(val text: ReadoutText, val args: List<Any> = emptyList()) {

    /** The English text with the arguments. */
    val english: String get() = formatText(text.english, args)

    companion object {
        private val SPECIFIER = Regex("%(\\d+)\\$[ds]")

        /** Puts [args] into the specifiers `%n$d` and `%n$s` of [pattern]. */
        fun formatText(pattern: String, args: List<Any>): String =
            SPECIFIER.replace(pattern) { match ->
                val index = match.groupValues[1].toInt() - 1
                args.getOrNull(index)?.toString() ?: match.value
            }
    }
}
