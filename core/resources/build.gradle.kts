import org.superdro.composite.task.LocalizationCheckTask
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns(serialization = false)
    // androidResources puts the Compose resources into the Android assets. Compose
    // Multiplatform 1.10.3 loads a font on Android only from the assets (Font(path, assets)).
    superDroTargets("org.superdro.core.resources", androidResources = true)
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.compose.components.resources)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // TestEnvironment gets the resource environment from a composition that Skiko
                // draws in memory.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "org.superdro.core.resources"
    generateResClass = always
}

// The CI runner has no display. The tests also run with no display on a computer that has one,
// so that a test that needs a display fails here too.
tasks.named<Test>("jvmTest") {
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}

val composeResourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources")

val localizationCheck = tasks.register<LocalizationCheckTask>("localizationCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compares each string file of values/ with the file of the same name in each locale."
    stringFiles.from(
        fileTree(composeResourcesDir) {
            include("values/strings*.xml", "values-*/strings*.xml")
        }
    )
    baseLocale = "values"
    // The apps copy these files into the Android resources (see app/*/build.gradle.kts). Only
    // these files need a backslash before each apostrophe.
    androidResourceFiles = setOf("strings_app.xml", "strings_probe.xml")
    // Each locale that the app ships. A locale with no directory gives no
    // file, so only this list can report it.
    expectedLocales = setOf(
        "values-de",
        "values-es",
        "values-fr",
        "values-nl",
        "values-pt-rBR",
    )
    // The names that stay the same in each locale. A bare name covers each locale. The form
    // `<locale>:<name>` covers one locale. A value with only format specifiers or punctuation
    // needs no entry. Only names, units, and words that are really the same go here: a text
    // that a language writes in a different form needs a translation.
    untranslatedAllowList = setOf(
        // The brand names.
        "app_name",
        "probe_app_name",
        // Names of link types that each language uses.
        "app_link_bluetooth",
        "app_link_usb",
        "values-es:app_link_wifi",
        "values-fr:app_link_wifi",
        "values-pt-rBR:app_link_wifi",
        // German and Dutch use the English words.
        "values-de:app_link_demo",
        "values-de:app_status_offline",
        "values-nl:app_link_demo",
        "values-nl:app_menu_adapters",
        "values-nl:app_status_offline",
    ) + setOf(
        // The names, units, symbols, axis letters, and format texts that are the same in each
        // locale: product and codec names (Mitutoyo SPC, MJPEG, JSON), units (mm, ms, TPI, m/min),
        // axis labels (X, ΔX, ABS, INC, 3D), and the names STANDARD and LEGACY of the two pattern
        // angle conventions.
        "adapter_input_aux1", "adapter_input_aux2", "adapter_mode_bcd7", "adapter_mode_bin6",
        "adapter_mode_igaging_spc", "adapter_mode_mitutoyo_spc", "adapter_unit_ms", "calibration_unit_mm",
        "camera_codec_h264", "camera_codec_mjpeg", "camera_codec_yuy2", "data_output_format_json",
        "data_output_interface_vpn", "data_output_timestamp_iso", "dataoutput_age_minutes", "dataoutput_age_seconds",
        "edgefinder_auto", "edgefinder_zauto_button", "encoder_axis_x", "encoder_axis_y", "encoder_axis_z",
        "encoder_report_linear_term_value", "files_log_label_dx", "files_log_label_dy", "fn_unit_mm", "fns_absolute",
        "fns_incremental", "graphics_zoom", "import_row_point", "measure_result_dx", "measure_result_dy",
        "probe_link_bluetooth", "probelib_unit_mm", "readout_unit_m_min", "readout_unit_mm", "refimage_nudge_step",
        "sd_3d", "settings_machine_angle_legacy", "settings_machine_angle_standard", "thrs_tpi", "tools_row_lathe",
    ) + listOf(
        // German: the words that German writes in the same form, for example "Name", "Adapter", "Firmware", "Status".
        "adapter_alias", "adapter_mode_igaging21", "camera_button_ok", "data_output_format",
        "data_output_mqtt_layout", "data_output_port", "data_output_status_warning", "data_output_unit_meter",
        "data_output_unit_millimeter", "dataoutput_age_hours", "dataoutput_clients", "dataoutput_section_status",
        "encoder_adapter", "encoder_diag_adapter", "encoder_diag_firmware", "encoder_kind_linear",
        "encoder_section_encoder", "files_log_label_position", "files_log_system", "fn_unit_inch", "fnp_column_name",
        "fnp_sub_datum", "frame_update_available", "graphics_add_guide_position", "measure_instrument_in_use", "measure_instrument_label",
        "measure_result_position", "measure_sheet_system", "probe_filter", "probe_tab_test", "probe_tablet",
        "probe_tcp_host", "probelib_field_name", "probelib_port_value", "probelib_unit_inch", "sd_edit_name",
        "sd_edit_tags", "settings_about_version", "settings_app_theme_system", "settings_logs_level_info",
        "settings_profiles_online", "thrs_start_z", "thrs_tag", "tools_field_name", "tools_row_id", "ws_datum_name",
        "ws_pair_points",
    ).map { name -> "values-de:$name" } + listOf(
        // Spanish: the words that Spanish writes in the same form, for example "Error", "Vertical", "Incremental".
        "camera_mode_format", "data_output_field_x_incremental", "data_output_field_y_incremental",
        "data_output_field_z_incremental", "data_output_interface_wifi", "dataoutput_age_hours",
        "edgefinder_incremental", "encoder_diag_firmware", "encoder_diag_no", "encoder_kind_angular",
        "encoder_map_spread", "fn_error_title", "fn_unit_inch", "graphics_add_guide_horizontal",
        "graphics_add_guide_vertical", "probe_link_tcp", "probe_no", "probe_tcp_host", "probelib_unit_inch",
        "settings_logs_level_error", "tools_units_imperial", "ws_pair_points", "ws_source_sub_datum",
    ).map { name -> "values-es:$name" } + listOf(
        // French: the words that French writes in the same form, for example "Distance", "Angle", "Position", "Note".
        "adapter_channel_mode", "adapter_mode_quadrature", "adapter_probe_auto", "camera_button_ok",
        "data_output_activation", "data_output_clients", "data_output_format", "data_output_interface_wifi",
        "data_output_port", "data_output_transport", "data_output_unit_micron", "data_output_unit_radians",
        "dataoutput_age_hours", "dataoutput_clients", "dataoutput_machine", "drawingui_type_arc",
        "drawingui_type_point", "encoder_map_tab_points", "encoder_report_passes", "encoder_wizard_passes",
        "files_log_label_distance", "files_log_label_included_angle", "files_log_label_position", "fn_preview_count",
        "fnp_points_title", "fns_direction", "fns_distance", "fns_mode", "graphics_add_guide_horizontal",
        "graphics_add_guide_position", "graphics_add_guide_vertical", "graphics_layer_guides",
        "graphics_rotate_angle", "graphics_rotate_pivot", "import_row_guide", "measure_align_angle",
        "measure_align_point_a", "measure_align_point_b", "measure_instrument_label", "measure_log_col_note",
        "measure_result_angle", "measure_result_distance", "measure_result_position", "measure_slot_number",
        "measure_verb_point", "probe_link_tcp", "probe_options", "probe_tab_console", "probelib_field_type",
        "probelib_port_value", "sd_edit_note", "sd_guide", "sd_shape_arc", "settings_about_version",
        "settings_app_group_machines", "settings_keys_action", "settings_logs_level_info",
        "settings_machine_group_machine", "settings_machine_section_communication", "thrs_type",
        "tools_field_description", "ws_datum_note",
    ).map { name -> "values-fr:$name" } + listOf(
        // Dutch: the words that Dutch writes in the same form, for example "Camera", "Filter", "Status", "Type".
        "adapter_field_model", "adapter_import_model", "camera_button_ok", "camera_dialog_title",
        "camera_mode_format", "camera_screen_title", "camera_setup_camera", "data_output_clients",
        "data_output_delimiter_tab", "data_output_field_label", "data_output_interval", "data_output_transport",
        "data_output_unit_inch", "data_output_unit_meter", "data_output_unit_millimeter", "dataoutput_clients",
        "dataoutput_machine", "dataoutput_records", "dataoutput_section_status", "edgefinder_probe_type_camera",
        "encoder_adapter", "encoder_backlash_constant_mode", "encoder_diag_adapter", "encoder_diag_firmware",
        "encoder_map_tab_curve", "encoder_section_encoder", "files_log_label_supplement", "files_probe_type_camera",
        "fn_unit_inch", "fnp_axis_diameter", "frame_update_available", "graphics_layer_camera", "measure_help", "measure_instrument_in_use",
        "measure_instrument_label", "measure_type_camera", "probe_filter", "probe_link_tcp", "probe_tab_console",
        "probe_tab_test", "probe_tablet", "probe_tcp_host", "probelib_field_type", "probelib_section_offsets",
        "probelib_unit_inch", "sd_edit_diameter", "settings_app_group_machines", "settings_machine_group_machine",
        "settings_machine_label", "settings_machine_section_camera", "settings_profiles_online", "thrs_results",
        "thrs_start_z", "thrs_tag", "thrs_type", "tools_row_id", "ws_pair_points", "ws_source_sub_datum",
    ).map { name -> "values-nl:$name" } + listOf(
        // Brazilian Portuguese: the words that Brazilian Portuguese writes in the same form, for example "Status", "Console", "Zero".
        "adapter_probe_summary", "camera_button_ok", "camera_mode_format", "data_output_field_x_incremental",
        "data_output_field_y_incremental", "data_output_field_z_incremental", "data_output_interface_wifi",
        "dataoutput_age_hours", "dataoutput_section_status", "edgefinder_incremental", "encoder_diag_firmware",
        "encoder_kind_angular", "encoder_kind_linear", "fnp_source_zero", "fns_zero",
        "graphics_add_guide_horizontal", "graphics_add_guide_vertical", "probe_link_tcp", "probe_link_usb",
        "probe_tab_console", "probe_tablet", "probe_tcp_host", "tools_units_imperial", "ws_pair_points",
        "ws_source_sub_datum",
    ).map { name -> "values-pt-rBR:$name" }
    reportFile = layout.buildDirectory.file("reports/localization/strings.txt")
}

tasks.named("check") {
    dependsOn(localizationCheck)
}
