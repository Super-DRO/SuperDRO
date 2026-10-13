package org.superdro.core.store.files

/**
 * The texts of the import and export files.
 *
 * Each key has the name of a string resource in `core:resources`
 * (`values/strings_files.xml` and the five translations). [english] is the text of the base
 * locale. A test makes sure that the two texts are the same. The UI gets the translated text by
 * [resourceName]. `core:store` does not depend on Compose, so it keeps the English text for the
 * tests and for the default text export.
 *
 * A pattern can have the arguments `%1$s` and `%1$d`, as a string resource.
 */
enum class MessageKey(val resourceName: String, val english: String) {
    // File checks.
    FileEmpty("files_file_empty", "The file has no content."),
    FileTooLarge("files_file_too_large", "The file is too large to import. The limit is %1\$d bytes."),
    FileBinary("files_file_binary", "The file is not a text file."),
    FileTooNew("files_file_too_new", "A newer version made this file (format %1\$s). Update SuperDRO to import it."),
    FileSemicolon("files_file_semicolon", "The values in the file are separated by “;”. Save the file with “,” between the values."),
    FileNotCsv("files_file_not_csv", "The app cannot read the file as CSV."),
    FileNotJson("files_file_not_json", "The app cannot read the file as JSON."),
    FileWrongKind("files_file_wrong_kind", "The file contains “%1\$s”. This import needs “%2\$s”."),
    FileUnknownKind("files_file_unknown_kind", "The app does not know the type of this file."),
    FileFailed("files_file_failed", "The import stopped because of an error: %1\$s"),
    ColumnMissing("files_column_missing", "The header does not have the required column “%1\$s”."),
    NoRows("files_no_rows", "The file has no data rows."),
    NothingUsable("files_nothing_usable", "No row of the file can be imported."),
    MachineMismatch("files_machine_mismatch", "The file is for the machine type %1\$s. This profile has the machine type %2\$s."),
    UnitsUndeclared("files_units_undeclared", "The file does not give its unit. The import uses the unit of the profile (%1\$s)."),
    UnitsOverridden("files_units_overridden", "The file gives the unit %1\$s. The import uses the unit that you selected (%2\$s)."),
    UnitsUnknown("files_units_unknown", "The unit “%1\$s” of the file is not known."),
    LatheYIgnored("files_lathe_y_ignored", "This profile is a lathe. The import ignores the y values."),
    LegacyLatheRows("files_legacy_lathe_rows", "Some rows have one value more than the header. The import reads the last value as the Z offset."),

    // Row problems.
    Row("files_row", "Row %1\$d"),
    NameBlank("files_name_blank", "Name is blank"),
    NameTooLong("files_name_too_long", "The name has %1\$d characters. The limit is %2\$d characters."),
    DescriptionTooLong("files_description_too_long", "The description has %1\$d characters. The limit is %2\$d characters."),
    ValueBlank("files_value_blank", "The value of “%1\$s” is blank."),
    ValueNotNumber("files_value_not_number", "The value of “%1\$s” is not a number: “%2\$s”."),
    ValueNotYesNo("files_value_not_yes_no", "The value of “%1\$s” must be yes or no: “%2\$s”."),
    ToolDuplicateName("files_tool_duplicate_name", "Tool offset with this name already exists in this file."),
    ToolDuplicateId("files_tool_duplicate_id", "An earlier row has the same ID “%1\$s”."),
    ToothCountNotWhole("files_tooth_count_not_whole", "The tooth count must be a whole number, 0 or more: “%1\$s”."),
    DiameterZero("files_diameter_zero", "The diameter must not be 0."),
    NoCoordinate("files_no_coordinate", "The row has no coordinate."),
    DuplicatePoint("files_duplicate_point", "An earlier row of the file has the same point."),
    ToolImportFinished("files_tool_import_finished", "Tool import finished: %1\$d tools added, %2\$d tools updated."),
    SubDatumImportFinished(
        "files_subdatum_import_finished",
        "Sub-datum import finished: %1\$d points added, %2\$d points updated, %3\$d guides added.",
    ),

    // Settings files.
    SettingInternal("files_setting_internal", "“%1\$s” is internal state, not a setting. The import ignores it."),
    SettingUnknown("files_setting_unknown", "“%1\$s” is not a setting that SuperDRO can import."),
    SettingInvalid("files_setting_invalid", "“%2\$s” is not a valid value for “%1\$s”. The import uses the default value “%3\$s”."),
    SettingInputNotPresent(
        "files_setting_input_not_present",
        "Input %2\$s of “%1\$s” gives no data. The import uses the default input “%3\$s”.",
    ),
    SettingSecondaryOff(
        "files_setting_secondary_off",
        "Input %2\$s of “%1\$s” gives no data. The import turns the secondary input off.",
    ),
    SettingSameInput(
        "files_setting_same_input",
        "The primary input and the secondary input of “%1\$s” are the same (%2\$s). The import turns the secondary input off.",
    ),
    SettingSecondaryNoInput(
        "files_setting_secondary_no_input",
        "The secondary input of “%1\$s” is on, but it has no input letter. The import turns the secondary input off.",
    ),
    SettingSharedInput("files_setting_shared_input", "“%1\$s” and “%2\$s” both use input %3\$s."),
    SettingNoAxis("files_setting_no_axis", "No output axis is enabled. The import enables X."),
    SettingNotMapped("files_setting_not_mapped", "SuperDRO has no setting for “%1\$s”. The import ignores it."),
    SettingLinkType("files_setting_link_type", "“%1\$s” is not a profile setting in SuperDRO. The link types are app settings."),
    SettingWifiNotMapped("files_setting_wifi_not_mapped", "“%1\$s” has no effect, because the profile has no Wi-Fi link."),
    SettingMachineTypeChange("files_setting_machine_type_change", "The machine type changes from %1\$s to %2\$s."),
    SettingNoneUsable("files_setting_none_usable", "The file has no setting that the import can use."),
    SettingZeroPoints(
        "files_setting_zero_points",
        "The file has no zero points and no workspace state. Set the zero points again after the import.",
    ),
    FieldInvalid("files_field_invalid", "The value of “%1\$s” is not valid. The import uses the default value."),
    FieldUnknown("files_field_unknown", "“%1\$s” is not a setting of this SuperDRO version. The import ignores it."),

    // Where the probe values go.
    ProbeToProfile("files_probe_to_profile", "“%1\$s” = %2\$s goes to the probe settings of this machine profile."),
    ProbeToAdapter("files_probe_to_adapter", "“%1\$s” = %2\$s goes to the probe settings of the adapter profile."),
    ProbeToProbeProfile("files_probe_to_probe_profile", "“%1\$s” = %2\$s goes to a probe profile in the probe library."),
    ProbeAdapterValues(
        "files_probe_adapter_values",
        "The probe contact type %1\$s and the debounce of %2\$d ms go to the adapter profile.",
    ),
    ProbeProfilesValues("files_probe_profiles_values", "%1\$d probe profiles go to the probe library of this machine profile."),

    // Adapter profile files.
    AdapterUnknownInput("files_adapter_unknown_input", "SuperDRO does not use input “%1\$s”. The import ignores it."),
    AdapterDuplicateInput(
        "files_adapter_duplicate_input",
        "The file has input %1\$s more than one time. The import uses the first one.",
    ),
    AdapterBadResolution(
        "files_adapter_bad_resolution",
        "The resolution “%2\$s” of input %1\$s is not usable. The import skips this input.",
    ),
    AdapterInvalidValue(
        "files_adapter_invalid_value",
        "“%3\$s” is not a valid value for “%2\$s” of input %1\$s. The import uses the default value “%4\$s”.",
    ),
    AdapterMapOnScale(
        "files_adapter_map_on_scale",
        "Input %1\$s has the encoder type %2\$s. A backlash map is permitted only on a leadscrew encoder. " +
            "The import uses no backlash compensation.",
    ),
    AdapterMapMissing(
        "files_adapter_map_missing",
        "Input %1\$s uses a backlash map, but the file has no usable map. The import uses no backlash compensation.",
    ),
    AdapterBacklashExceedsTurn(
        "files_adapter_backlash_exceeds_turn",
        "The backlash of input %1\$s is not less than one leadscrew turn. The import uses no backlash compensation.",
    ),
    AdapterErrorMapRefused("files_adapter_error_map_refused", "The error map of input %1\$s is not valid. The import does not use it."),
    AdapterBacklashMapRefused(
        "files_adapter_backlash_map_refused",
        "The backlash map of input %1\$s is not valid. The import does not use it.",
    ),
    AdapterModelMismatch(
        "files_adapter_model_mismatch",
        "The file is for the adapter model “%1\$s”. This adapter has the model “%2\$s”.",
    ),
    AdapterNoInputs("files_adapter_no_inputs", "The file has no inputs."),
    NameCut("files_name_cut", "The name has more than %1\$d characters. The import cuts it to %1\$d characters."),

    // Map texts.
    MapMalformedLine("files_map_malformed_line", "Line %1\$d is not a pair of whole numbers."),
    MapDecimalValues("files_map_decimal_values", "The map has decimal values. Use whole counts."),
    MapTooManyPoints("files_map_too_many_points", "The map has more than %1\$d points."),
    MapNoPoints("files_map_no_points", "The map has no points."),
    MapDuplicatePosition("files_map_duplicate_position", "Position %1\$d is in the map more than one time."),
    MapReversal("files_map_reversal", "The corrected position moves back between positions %1\$d and %2\$d."),
    MapDeadBand("files_map_dead_band", "The corrected position does not move between positions %1\$d and %2\$d."),
    MapNegativeBacklash("files_map_negative_backlash", "The backlash at position %1\$d is less than 0."),
    MapBacklashExceedsTurn(
        "files_map_backlash_exceeds_turn",
        "The backlash at position %1\$d is not less than one leadscrew turn.",
    ),

    // Probe profile files.
    ProbeUnknownType("files_probe_unknown_type", "SuperDRO has no probe type “%1\$s”. The import skips this profile."),
    ProbeInputDetached("files_probe_input_detached", "“%1\$s” is not a probe port. The profile gets no port."),
    ProbeInputDefault(
        "files_probe_input_default",
        "“%1\$s” is not a probe port. A touch probe needs a port, so the profile gets port %2\$s.",
    ),
    ProbeSessionUnsigned(
        "files_probe_session_unsigned",
        "The calibration session has no digest. The import keeps the session, but it cannot check it.",
    ),
    ProbeSessionDropped(
        "files_probe_session_dropped",
        "The digest of the calibration session is not correct. The import does not use the session.",
    ),
    ProbeOtherProfile("files_probe_other_profile", "The profiles come from “%1\$s”. You import them into “%2\$s”."),
    ProbeNoProfiles("files_probe_no_profiles", "The file has no probe profiles."),
    ProbeIdRepeated("files_probe_id_repeated", "An earlier row has the same ID. The import adds this row as a new profile."),
    ProbeTypeTouchProbe("files_probe_type_touch_probe", "Touch probe"),
    ProbeTypeEdgeFinder("files_probe_type_edge_finder", "2D edge finder"),
    ProbeTypeToolSetter("files_probe_type_tool_setter", "Tool height setter"),
    ProbeTypeCamera("files_probe_type_camera", "Camera"),

    // The import report.
    ReportTitle("files_report_title", "Import report"),
    ReportSummary("files_report_summary", "Imported: %1\$d. Not imported: %2\$d. Warnings: %3\$d."),
    ReportNotMapped("files_report_not_mapped", "Items that the import did not map"),
    ReportDefaults("files_report_defaults", "Values that got the default"),
    ReportProbe("files_report_probe", "Where the probe values go"),
    ReportWarnings("files_report_warnings", "Other warnings"),
    ReportSkippedRows("files_report_skipped_rows", "Rows that the import skipped"),

    // The inspection sheet.
    LogTitle("files_log_title", "SuperDRO — Inspection Log"),
    LogWorkspace("files_log_workspace", "Workspace: %1\$s"),
    LogNote("files_log_note", "Note: %1\$s"),
    LogSystem("files_log_system", "System: %1\$s"),
    LogLabelDiameter("files_log_label_diameter", "Circle"),
    LogLabelCenter("files_log_label_center", "Center"),
    LogLabelDistance("files_log_label_distance", "Distance"),
    LogLabelDx("files_log_label_dx", "ΔX"),
    LogLabelDy("files_log_label_dy", "ΔY"),
    LogLabelMidpoint("files_log_label_midpoint", "Midpoint"),
    LogLabelIncludedAngle("files_log_label_included_angle", "Angle"),
    LogLabelSupplement("files_log_label_supplement", "Supplement"),
    LogLabelCorner("files_log_label_corner", "Corner"),
    LogLabelAngleToX("files_log_label_angle_to_x", "Angle to X"),
    LogLabelAngleToY("files_log_label_angle_to_y", "Angle to Y"),
    LogLabelPosition("files_log_label_position", "Position"),
}

/**
 * One text of an import or an export, with its arguments. The UI resolves it with the string
 * resource [MessageKey.resourceName] and [args].
 */
data class ImportMessage(val key: MessageKey, val args: List<Any> = emptyList()) {
    constructor(key: MessageKey, vararg args: Any) : this(key, args.toList())
}

/** Gives the text of an [ImportMessage]. The UI gives a resolver that uses the string resources. */
fun interface ImportTexts {
    fun text(message: ImportMessage): String

    companion object {
        /** The English texts of [MessageKey.english]. */
        val English: ImportTexts = ImportTexts { message -> formatPattern(message.key.english, message.args) }
    }
}

private val ARGUMENT = Regex("""%(\d+)\$[sd]""")

/**
 * Puts [args] into [pattern]. `%1$s` and `%1$d` give the text of the first argument. This is the
 * rule of the Compose string resources.
 */
fun formatPattern(pattern: String, args: List<Any>): String = ARGUMENT.replace(pattern) { match ->
    val index = match.groupValues[1].toInt() - 1
    args.getOrNull(index)?.toString() ?: match.value
}
