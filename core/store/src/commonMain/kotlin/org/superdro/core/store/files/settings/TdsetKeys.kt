package org.superdro.core.store.files.settings

import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.TachometerMode
import org.superdro.core.store.files.csv.CsvNumbers

/** The value rule of a `.tdset` key. */
internal sealed interface KeyRule {
    /** `true`, `false`, `yes`, `no`, `on`, `off`, `1`, `0` in any case, a JSON boolean, or the numbers 1 and 0. */
    data object Flag : KeyRule

    /** A JSON string, trimmed. A boolean or a number is not valid. */
    data object FreeText : KeyRule

    /** A whole number from 0 to [count] − 1. */
    data class Ordinal(val count: Int) : KeyRule

    /** One of [values], in lowercase. */
    data class OneOf(val values: List<String>) : KeyRule

    /** A whole number from [min] to [max]. */
    data class Range(val min: Int, val max: Int) : KeyRule

    /** The primary input of an axis: one of `x y z w u v`. */
    data object AxisInput : KeyRule

    /** The secondary input of an axis: empty, or one of `x y z w u v`. */
    data object SecondaryInput : KeyRule

    /** The probe port: `p` or `q`. */
    data object ProbePort : KeyRule

    /** Numbers separated by spaces. */
    data object Presets : KeyRule

    /** The angle reference of the auxiliary readout: 0, 90, or 180. */
    data object AngleReference : KeyRule

    /** A time in seconds, 0 or more. */
    data object Seconds : KeyRule

    /** A length in µm. */
    data object Microns : KeyRule

    /** A whole number from 1 to 10. */
    data object SampleCount : KeyRule

    /** A length in µm, more than 0. */
    data object Spread : KeyRule
}

/** Where the value of a key goes. */
internal enum class KeyTarget {
    /** A setting of the machine profile. */
    Profile,

    /** A key with no SuperDRO setting. */
    NotMapped,

    /** A link type switch. SuperDRO keeps the link types in the app settings. */
    LinkType,

    /** The Wi-Fi host or port: the TCP link of the profile. */
    Wifi,

    /** A probe value of the adapter profile. */
    ProbeAdapter,

    /** A probe value of a probe profile. */
    ProbeProfile,
}

/** One permitted key. [probe] is true for a key that goes to the probe settings of the profile. */
internal data class KeyDef(val name: String, val rule: KeyRule, val target: KeyTarget = KeyTarget.Profile, val probe: Boolean = false)

/** The value of a key after its rule: valid, a letter that gives no data, or not valid. */
internal sealed interface KeyValue {
    data class Valid(val value: Any) : KeyValue
    data class NotPresent(val letter: String) : KeyValue
    data object Invalid : KeyValue
}

/**
 * The permitted keys of a `.tdset` file: the 123 keys of the transfer list, in its sequence, and
 * the probe keys.
 */
internal object TdsetKeys {

    private val BAUD_RATES = AdapterAddress.UsbSerial.BAUD_RATES.map(Int::toString)
    private val Y_TYPES = listOf("tailstock", "compound", "other")
    private const val BUTTON_MODES = 6

    private fun buttons(axis: String, target: KeyTarget): List<KeyDef> = (1..3).flatMap { n ->
        listOf(
            KeyDef("${axis}_button_${n}_mode", KeyRule.Ordinal(BUTTON_MODES), target),
            KeyDef("${axis}_button_${n}_show_expanded", KeyRule.Flag, target),
            KeyDef("${axis}_button_${n}_show_collapsed", KeyRule.Flag, target),
        )
    }

    private fun linear(axis: String): List<KeyDef> = listOf(
        KeyDef("${axis}_enable", KeyRule.Flag),
        KeyDef("${axis}_label", KeyRule.FreeText),
        KeyDef("${axis}_prefix", KeyRule.AxisInput),
        KeyDef("${axis}_invert", KeyRule.Flag),
        KeyDef("${axis}_secondary_enable", KeyRule.Flag),
        KeyDef("${axis}_prefix_secondary", KeyRule.SecondaryInput),
        KeyDef("${axis}_invert_secondary", KeyRule.Flag),
    )

    /** The 123 keys of the transfer list. */
    val TRANSFER: List<KeyDef> = buildList {
        add(KeyDef("machine_type", KeyRule.Ordinal(3)))
        add(KeyDef("use_metric_mode", KeyRule.Flag))
        add(KeyDef("use_global_tool_library", KeyRule.Flag))
        add(KeyDef("display_format", KeyRule.Ordinal(6)))
        add(KeyDef("display_font", KeyRule.Ordinal(3)))
        listOf("show_sliders", "mute_sounds", "short_press_zero_set", "activate_added_subdatum", "legacy_angle_datum")
            .forEach { name -> add(KeyDef(name, KeyRule.Flag)) }
        listOf("bluetooth_connection_enabled", "usb_connection_enabled", "wifi_connection_enabled")
            .forEach { name -> add(KeyDef(name, KeyRule.Flag, KeyTarget.LinkType)) }
        add(KeyDef("usb_baud_rate", KeyRule.OneOf(BAUD_RATES)))
        add(KeyDef("wifi_ip_address", KeyRule.FreeText, KeyTarget.Wifi))
        add(KeyDef("wifi_port", KeyRule.Range(1, 65_535), KeyTarget.Wifi))
        add(KeyDef("disable_two_way_communications", KeyRule.Flag))
        add(KeyDef("data_output_enabled", KeyRule.Flag))
        addAll(linear("x"))
        addAll(buttons("x", KeyTarget.Profile))
        addAll(linear("y"))
        add(KeyDef("y_type", KeyRule.OneOf(Y_TYPES)))
        add(KeyDef("y_vectoring", KeyRule.Flag))
        add(KeyDef("y_angle_reference", KeyRule.AngleReference))
        addAll(buttons("y", KeyTarget.Profile))
        addAll(linear("z"))
        addAll(buttons("z", KeyTarget.Profile))
        add(KeyDef("angular_enable", KeyRule.Flag))
        add(KeyDef("angular_label", KeyRule.FreeText))
        add(KeyDef("angular_prefix", KeyRule.AxisInput))
        add(KeyDef("angular_invert", KeyRule.Flag))
        addAll(buttons("angular", KeyTarget.Profile))
        add(KeyDef("tach_enable", KeyRule.Flag))
        add(KeyDef("tach_label", KeyRule.FreeText))
        add(KeyDef("tach_mode", KeyRule.Ordinal(3)))
        add(KeyDef("tach_invert", KeyRule.Flag))
        add(KeyDef("tach_presets", KeyRule.Presets))
        addAll(buttons("tach", KeyTarget.NotMapped))
        add(KeyDef("caliper_enable", KeyRule.Flag))
        add(KeyDef("caliper_label", KeyRule.FreeText, KeyTarget.NotMapped))
        add(KeyDef("caliper_prefix", KeyRule.AxisInput))
        add(KeyDef("caliper_invert", KeyRule.Flag))
        addAll(buttons("caliper", KeyTarget.NotMapped))
        listOf(
            "show_radius", "show_height", "show_height_setter", "show_edge_finder", "show_hole_circle", "show_arbitrary_circle",
            "show_hole_grid", "show_equidistant_grid", "show_dimension_measure", "show_cut_radius", "show_measure",
        ).forEach { name -> add(KeyDef(name, KeyRule.Flag)) }
        add(KeyDef("show_calculators", KeyRule.Flag, KeyTarget.NotMapped))
        add(KeyDef("show_head_tilt", KeyRule.Flag))
        add(KeyDef("show_compound_angle", KeyRule.Flag))
    }

    /** The probe keys. A 3.32.00 file does not have them. */
    val PROBE: List<KeyDef> = listOf(
        KeyDef("probe_enable", KeyRule.Flag, probe = true),
        KeyDef("probe_prefix", KeyRule.ProbePort, probe = true),
        KeyDef("probe_invert", KeyRule.Flag, KeyTarget.ProbeAdapter),
        KeyDef("probe_debounce_seconds", KeyRule.Seconds, KeyTarget.ProbeAdapter),
        KeyDef("probe_edge_finder_enable", KeyRule.Flag, probe = true),
        KeyDef("probe_x_offset_compensation_microns", KeyRule.Microns, KeyTarget.ProbeProfile),
        KeyDef("probe_y_offset_compensation_microns", KeyRule.Microns, KeyTarget.ProbeProfile),
        KeyDef("probe_edge_finder_sample_count", KeyRule.SampleCount, KeyTarget.ProbeProfile),
        KeyDef("probe_edge_finder_sample_spread_microns", KeyRule.Spread, KeyTarget.ProbeProfile),
        KeyDef("probe_tool_setter_enable", KeyRule.Flag, probe = true),
    )

    val ALL: Map<String, KeyDef> = (TRANSFER + PROBE).associateBy(KeyDef::name)

    /** The prefixes of the keys that are internal state, never a setting. */
    val INTERNAL_PREFIXES: List<String> = listOf("g_state", "c_state", "w_state")

    fun isInternal(key: String): Boolean = INTERNAL_PREFIXES.any { prefix -> key.startsWith(prefix, ignoreCase = true) }

    /** Applies the rule of [def] to [element]. */
    fun parse(def: KeyDef, element: JsonElement): KeyValue {
        val primitive = element as? JsonPrimitive ?: return KeyValue.Invalid
        if (primitive is JsonNull) return KeyValue.Invalid
        val text = primitive.content.trim()
        return when (val rule = def.rule) {
            KeyRule.Flag -> flag(primitive)?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.FreeText -> if (primitive.isString) KeyValue.Valid(text) else KeyValue.Invalid
            is KeyRule.Ordinal -> wholeNumber(primitive)?.takeIf { it in 0 until rule.count }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            is KeyRule.OneOf -> text.lowercase().takeIf { it in rule.values }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            is KeyRule.Range -> wholeNumber(primitive)?.takeIf { it in rule.min..rule.max }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.AxisInput -> letter(text, Channels.axisChannels, allowEmpty = false)
            KeyRule.SecondaryInput -> letter(text, Channels.axisChannels, allowEmpty = true)
            KeyRule.ProbePort -> letter(text, Channels.probeChannels, allowEmpty = false)
            KeyRule.Presets -> presets(text)?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.AngleReference -> wholeNumber(primitive)?.takeIf { it == 0 || it == 90 || it == 180 }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.Seconds -> number(primitive)?.takeIf { it >= 0.0 }?.let { KeyValue.Valid((it * 1000.0).roundToLong()) } ?: KeyValue.Invalid
            KeyRule.Microns -> number(primitive)?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.SampleCount -> wholeNumber(primitive)
                ?.takeIf { it in ProbeProfile.MIN_SAMPLE_COUNT..ProbeProfile.MAX_SAMPLE_COUNT }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
            KeyRule.Spread -> number(primitive)?.takeIf { it > 0.0 }?.let { KeyValue.Valid(it) } ?: KeyValue.Invalid
        }
    }

    private fun flag(primitive: JsonPrimitive): Boolean? {
        if (!primitive.isString) {
            primitive.booleanOrNull?.let { return it }
            return when (primitive.doubleOrNull) {
                1.0 -> true
                0.0 -> false
                else -> null
            }
        }
        return when (primitive.content.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }
    }

    private fun number(primitive: JsonPrimitive): Double? {
        val value = if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
        return value?.takeIf(Double::isFinite)
    }

    private fun wholeNumber(primitive: JsonPrimitive): Int? {
        val value = number(primitive) ?: return null
        return if (value == floor(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
    }

    /**
     * A letter rule. A letter `a` to `f` gives [KeyValue.NotPresent]. For a probe
     * port, `r` and `s` also give [KeyValue.NotPresent].
     */
    private fun letter(text: String, permitted: List<Char>, allowEmpty: Boolean): KeyValue {
        if (text.isEmpty()) return if (allowEmpty) KeyValue.Valid("") else KeyValue.Invalid
        if (text.length != 1) return KeyValue.Invalid
        val letter = Channels.normalize(text[0])
        return when {
            letter in permitted -> KeyValue.Valid(letter.toString())
            Channels.isIgnored(letter) -> KeyValue.NotPresent(text.uppercase())
            else -> KeyValue.Invalid
        }
    }

    private fun presets(text: String): List<Double>? {
        val values = text.split(Regex("""\s+""")).filter(String::isNotEmpty).map { token -> token.toDoubleOrNull() }
        if (values.any { value -> value == null || !value.isFinite() || value < 0.0 }) return null
        return values.filterNotNull()
    }

    /** The text of a typed value for the preview. */
    fun display(value: Any?): String = when (value) {
        null -> ""
        is Double -> CsvNumbers.formatCompact(value * 1000.0, metric = true).removeSuffix(".0")
        is List<*> -> value.joinToString(" ") { item -> display(item) }
        else -> value.toString()
    }

    /**
     * Gives the value of each mapped key for [profile], in the domain of the key rules. The
     * import compares these values with the file values.
     */
    fun flatten(profile: MachineProfile): Map<String, Any?> = buildMap {
        put("machine_type", profile.machineType.ordinal)
        put("use_metric_mode", profile.metricDefault)
        put("use_global_tool_library", profile.useSharedToolLibrary)
        put("display_format", profile.display.readoutFormat.code)
        put("display_font", fontCode(profile.display.readoutFont))
        put("show_sliders", profile.display.showSliders)
        put("mute_sounds", profile.muteSounds)
        put("short_press_zero_set", profile.display.shortPressZero)
        put("activate_added_subdatum", profile.display.activateNewSubDatum)
        put("legacy_angle_datum", profile.patternAngleConvention == PatternAngleConvention.Legacy)
        put("usb_baud_rate", profile.usbBaudRate.toString())
        val tcp = profile.adapter as? AdapterAddress.Tcp
        put("wifi_ip_address", tcp?.host ?: "")
        put("wifi_port", tcp?.port ?: AdapterAddress.Tcp.DEFAULT_PORT)
        put("disable_two_way_communications", !profile.twoWayCommunication)
        put("data_output_enabled", profile.dataOutput.enabled)
        listOf("x" to profile.x, "y" to profile.y, "z" to profile.z).forEach { (axis, config) ->
            put("${axis}_enable", config.enabled)
            put("${axis}_label", config.label)
            put("${axis}_prefix", config.primary.channel.toString())
            put("${axis}_invert", config.primary.sign < 0)
            put("${axis}_secondary_enable", config.secondary != null)
            put("${axis}_prefix_secondary", config.secondary?.channel?.toString() ?: "")
            put("${axis}_invert_secondary", (config.secondary?.sign ?: 1) < 0)
            putButtons(axis, config.buttons)
        }
        put("y_type", auxiliaryName(profile.auxiliary.type))
        put("y_vectoring", profile.auxiliary.vectoring)
        put("y_angle_reference", profile.auxiliary.angleReferenceDegrees)
        put("angular_enable", profile.angular.enabled)
        put("angular_label", profile.angular.label)
        put("angular_prefix", profile.angular.primary.channel.toString())
        put("angular_invert", profile.angular.primary.sign < 0)
        putButtons("angular", profile.angular.buttons)
        put("tach_enable", profile.tachometer.enabled)
        put("tach_label", profile.tachometer.label)
        put("tach_mode", profile.tachometer.mode.ordinal)
        put("tach_invert", profile.tachometer.invert)
        put("tach_presets", profile.tachometer.presets)
        put("caliper_enable", profile.caliper.enabled)
        put("caliper_prefix", profile.caliper.input.channel.toString())
        put("caliper_invert", profile.caliper.input.sign < 0)
        val buttons = profile.functionButtons
        put("show_radius", buttons.radiusCompensation)
        put("show_height", buttons.heightCompensation)
        put("show_height_setter", buttons.zReference)
        put("show_edge_finder", buttons.edgeFinder)
        put("show_hole_circle", buttons.holeCircle)
        put("show_arbitrary_circle", buttons.arbitraryHoleCircle)
        put("show_hole_grid", buttons.holeGrid)
        put("show_equidistant_grid", buttons.equidistantArray)
        put("show_dimension_measure", buttons.dimensionMeasure)
        put("show_cut_radius", buttons.arcCut)
        put("show_measure", buttons.angleMeasure)
        put("show_head_tilt", buttons.headTilt)
        put("show_compound_angle", buttons.compoundAngle)
        put("probe_enable", profile.probe.enabled)
        put("probe_prefix", profile.probe.channel.toString())
        put("probe_edge_finder_enable", profile.probe.edgeFinderEnabled)
        put("probe_tool_setter_enable", profile.probe.toolSetterEnabled)
    }

    /** The defaults of the probe keys that do not go to the machine profile. */
    val PROBE_DEFAULTS: Map<String, Any> = mapOf(
        "probe_invert" to false,
        "probe_debounce_seconds" to 200L,
        "probe_x_offset_compensation_microns" to 0.0,
        "probe_y_offset_compensation_microns" to 0.0,
        "probe_edge_finder_sample_count" to ProbeProfile.DEFAULT_SAMPLE_COUNT,
        "probe_edge_finder_sample_spread_microns" to ProbeProfile.DEFAULT_SPREAD_LIMIT_UM,
    )

    private fun MutableMap<String, Any?>.putButtons(axis: String, buttons: List<AxisButtonConfig>) {
        val defaults = AxisButtonConfig.defaults()
        (1..3).forEach { n ->
            val button = buttons.getOrNull(n - 1) ?: defaults[n - 1]
            put("${axis}_button_${n}_mode", button.mode.ordinal)
            put("${axis}_button_${n}_show_expanded", button.showExpanded)
            put("${axis}_button_${n}_show_collapsed", button.showCollapsed)
        }
    }

    /** `display_font`: 0 = DSEG7, 1 = monospace, 2 = the system font. Other fonts have no code. */
    fun fontCode(font: ReadoutFont): Int? = when (font) {
        ReadoutFont.Dseg7 -> 0
        ReadoutFont.PlatformMonospace -> 1
        ReadoutFont.System -> 2
        else -> null
    }

    fun font(code: Int): ReadoutFont = when (code) {
        0 -> ReadoutFont.Dseg7
        1 -> ReadoutFont.PlatformMonospace
        else -> ReadoutFont.System
    }

    fun machineType(ordinal: Int): MachineType = MachineType.entries[ordinal]

    fun auxiliaryName(type: AuxiliaryType): String = when (type) {
        AuxiliaryType.Tailstock -> "tailstock"
        AuxiliaryType.CompoundSlide -> "compound"
        AuxiliaryType.Other -> "other"
    }

    fun auxiliaryType(name: String): AuxiliaryType = when (name) {
        "compound" -> AuxiliaryType.CompoundSlide
        "other" -> AuxiliaryType.Other
        else -> AuxiliaryType.Tailstock
    }

    fun buttonMode(ordinal: Int): AxisButtonMode = AxisButtonMode.entries[ordinal]

    fun tachometerMode(ordinal: Int): TachometerMode = TachometerMode.entries[ordinal]
}
