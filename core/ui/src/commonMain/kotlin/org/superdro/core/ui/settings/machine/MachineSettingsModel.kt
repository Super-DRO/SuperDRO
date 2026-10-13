package org.superdro.core.ui.settings.machine

import androidx.compose.runtime.Immutable
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.FunctionButtonSettings
import org.superdro.core.model.InputConfig
import org.superdro.core.model.LinearAxisConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MuteScope
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.TransportKind
import org.superdro.core.readout.settings.AxisSettingsRules
import org.superdro.core.readout.settings.SettingsResult
import org.superdro.core.readout.settings.TachometerFields
import org.superdro.core.ui.settings.typechange.MachineTypeChangeUiState
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The groups of the machine settings screen. [groupsFor] gives the
 * groups of a machine type: the caliper only on a lathe, the probe only on a mill.
 */
enum class MachineSettingsGroup {
    Machine,
    Display,
    Functions,
    AxisX,
    AxisY,
    AxisZ,
    Angular,
    Caliper,
    Tachometer,
    Probe,
    Link;

    /** The output axis of an axis group, or null. */
    val axis: AxisId?
        get() = when (this) {
            AxisX -> AxisId.X
            AxisY -> AxisId.Y
            AxisZ -> AxisId.Z
            Angular -> AxisId.Angular
            Caliper -> AxisId.Caliper
            Tachometer -> AxisId.Tachometer
            else -> null
        }

    companion object {
        /** Gives the groups of the machine type [type], in the order of the screen. */
        fun groupsFor(type: MachineType): List<MachineSettingsGroup> = entries.filter { group ->
            when (group) {
                Caliper -> type.isLathe
                Probe -> !type.isLathe
                else -> true
            }
        }
    }
}

/** The text fields of the machine settings. The presenter holds the text of each field while the user types. */
enum class SettingsTextField {
    Name,
    LabelX,
    LabelY,
    LabelZ,
    LabelAngular,
    LabelTachometer,
    Presets,
    Pulses,
    BluetoothAddress,
    WifiHost,
    WifiPort;

    companion object {
        /** Gives the label field of [axis], or null for an axis with no label setting. */
        fun labelOf(axis: AxisId): SettingsTextField? = when (axis) {
            AxisId.X -> LabelX
            AxisId.Y -> LabelY
            AxisId.Z -> LabelZ
            AxisId.Angular -> LabelAngular
            AxisId.Tachometer -> LabelTachometer
            AxisId.Caliper -> null
        }
    }
}

/** A function strip switch (`show_*`). */
enum class FunctionSwitch(val millOnly: Boolean = false, val latheOnly: Boolean = false) {
    EdgeFinder(millOnly = true),
    ZReference(millOnly = true),
    RadiusCompensation(millOnly = true),
    HeightCompensation(millOnly = true),
    HoleCircle,
    ArbitraryHoleCircle(millOnly = true),
    HoleGrid,
    EquidistantArray,
    DimensionMeasure,
    AngleMeasure,
    ArcCut,
    HeadTilt(millOnly = true),
    CompoundAngle(latheOnly = true);

    /** True when the switch applies to a machine of [type] (both mill types). */
    fun appliesTo(type: MachineType): Boolean = when {
        millOnly -> !type.isLathe
        latheOnly -> type.isLathe
        else -> true
    }

    /** Gives the value of this switch in [settings]. */
    fun valueIn(settings: FunctionButtonSettings): Boolean = when (this) {
        EdgeFinder -> settings.edgeFinder
        ZReference -> settings.zReference
        RadiusCompensation -> settings.radiusCompensation
        HeightCompensation -> settings.heightCompensation
        HoleCircle -> settings.holeCircle
        ArbitraryHoleCircle -> settings.arbitraryHoleCircle
        HoleGrid -> settings.holeGrid
        EquidistantArray -> settings.equidistantArray
        DimensionMeasure -> settings.dimensionMeasure
        AngleMeasure -> settings.angleMeasure
        ArcCut -> settings.arcCut
        HeadTilt -> settings.headTilt
        CompoundAngle -> settings.compoundAngle
    }

    /** Gives [settings] with this switch set to [on]. */
    fun set(settings: FunctionButtonSettings, on: Boolean): FunctionButtonSettings = when (this) {
        EdgeFinder -> settings.copy(edgeFinder = on)
        ZReference -> settings.copy(zReference = on)
        RadiusCompensation -> settings.copy(radiusCompensation = on)
        HeightCompensation -> settings.copy(heightCompensation = on)
        HoleCircle -> settings.copy(holeCircle = on)
        ArbitraryHoleCircle -> settings.copy(arbitraryHoleCircle = on)
        HoleGrid -> settings.copy(holeGrid = on)
        EquidistantArray -> settings.copy(equidistantArray = on)
        DimensionMeasure -> settings.copy(dimensionMeasure = on)
        AngleMeasure -> settings.copy(angleMeasure = on)
        ArcCut -> settings.copy(arcCut = on)
        HeadTilt -> settings.copy(headTilt = on)
        CompoundAngle -> settings.copy(compoundAngle = on)
    }
}

/** The link types that the machine settings can select. A USB device comes only from the add-device sheet. */
val SETTINGS_LINK_TYPES: List<TransportKind> = listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp)

/**
 * The state of the machine settings screen of one profile.
 *
 * @property profile The stored settings of the profile, or null before the load (and after a
 *   delete).
 * @property texts The text of each text field that the user changed. A field with no text here
 *   shows the stored value ([textOf]).
 * @property errors The message of each text field with a value that the store does not get.
 * @property conflicts The input conflict warnings: a letter on two axes and the
 *   compound projection that does not apply.
 * @property muteScope The mute scope of the app settings: the switch of this machine applies
 *   only with [MuteScope.EachMachine].
 * @property tachometerPulses The pulses for each revolution of the input `t` (its encoder profile),
 *   or null when the profile has no adapter profile.
 * @property linkType The link type that the link group shows: the type of the stored address, or
 *   the type that the user selected and that has no valid address yet.
 */
@Immutable
data class MachineSettingsUiState(
    val loaded: Boolean = false,
    val profile: MachineProfile? = null,
    val group: MachineSettingsGroup = MachineSettingsGroup.Machine,
    val texts: Map<SettingsTextField, String> = emptyMap(),
    val errors: Map<SettingsTextField, UiText> = emptyMap(),
    val conflicts: List<UiText> = emptyList(),
    val muteScope: MuteScope = MuteScope.App,
    val tachometerPulses: Double? = null,
    val linkType: TransportKind? = null,
    val typeChange: MachineTypeChangeUiState? = null,
) {
    /** The groups of the machine type of the profile. */
    val groups: List<MachineSettingsGroup>
        get() = MachineSettingsGroup.groupsFor(profile?.machineType ?: MachineType.VerticalMill)

    /** The tachometer fields that show for the tachometer mode. */
    val tachometerFields: TachometerFields
        get() = AxisSettingsRules.tachometerFields(profile?.tachometer?.mode ?: TachometerMode.Sensor)

    /** True when the edge finder settings show: the probe is enabled. */
    val edgeFinderVisible: Boolean get() = AxisSettingsRules.edgeFinderSettingsVisible(profile?.probe?.enabled == true)

    /** Gives the text of [field]: the text of the user, else the stored value. */
    fun textOf(field: SettingsTextField): String = texts[field] ?: storedText(field)

    private fun storedText(field: SettingsTextField): String {
        val current = profile ?: return ""
        return when (field) {
            SettingsTextField.Name -> current.name
            SettingsTextField.LabelX -> current.x.label
            SettingsTextField.LabelY -> current.y.label
            SettingsTextField.LabelZ -> current.z.label
            SettingsTextField.LabelAngular -> current.angular.label
            SettingsTextField.LabelTachometer -> current.tachometer.label
            SettingsTextField.Presets -> PresetsText.format(current.tachometer.presets)
            SettingsTextField.Pulses -> tachometerPulses?.let(PresetsText::number).orEmpty()
            SettingsTextField.BluetoothAddress -> (current.adapter as? AdapterAddress.BluetoothSpp)?.mac.orEmpty()
            SettingsTextField.WifiHost -> (current.adapter as? AdapterAddress.Tcp)?.host.orEmpty()
            SettingsTextField.WifiPort -> ((current.adapter as? AdapterAddress.Tcp)?.port ?: AdapterAddress.Tcp.DEFAULT_PORT).toString()
        }
    }
}

/** Gives the linear axis config of [axis] (X, Y, or Z) of [profile]. */
fun MachineProfile.linear(axis: AxisId): LinearAxisConfig = when (axis) {
    AxisId.X -> x
    AxisId.Y -> y
    AxisId.Z -> z
    else -> error("$axis is not a linear axis.")
}

/** Gives [this] with the linear axis config of [axis] changed by [transform]. */
fun MachineProfile.withLinear(axis: AxisId, transform: (LinearAxisConfig) -> LinearAxisConfig): MachineProfile = when (axis) {
    AxisId.X -> copy(x = transform(x))
    AxisId.Y -> copy(y = transform(y))
    AxisId.Z -> copy(z = transform(z))
    else -> error("$axis is not a linear axis.")
}

/** Gives the action buttons of [axis] (a linear axis or the angular axis), or null. */
fun MachineProfile.buttonsOf(axis: AxisId): List<AxisButtonConfig>? = when (axis) {
    AxisId.X, AxisId.Y, AxisId.Z -> linear(axis).buttons
    AxisId.Angular -> angular.buttons
    else -> null
}

/** Gives [this] with the action buttons of [axis] changed by [transform]. */
fun MachineProfile.withButtons(axis: AxisId, transform: (List<AxisButtonConfig>) -> List<AxisButtonConfig>): MachineProfile = when (axis) {
    AxisId.X, AxisId.Y, AxisId.Z -> withLinear(axis) { config -> config.copy(buttons = transform(config.buttons)) }
    AxisId.Angular -> copy(angular = angular.copy(buttons = transform(angular.buttons)))
    else -> this
}

/** Gives the primary input of [axis]. */
fun MachineProfile.primaryOf(axis: AxisId): InputConfig = when (axis) {
    AxisId.X, AxisId.Y, AxisId.Z -> linear(axis).primary
    AxisId.Angular -> angular.primary
    AxisId.Caliper -> caliper.input
    AxisId.Tachometer -> InputConfig(Channels.T, if (tachometer.invert) -1 else 1)
}

/** True when [axis] is enabled in [this]. */
fun MachineProfile.enabled(axis: AxisId): Boolean = when (axis) {
    AxisId.X -> x.enabled
    AxisId.Y -> y.enabled
    AxisId.Z -> z.enabled
    AxisId.Angular -> angular.enabled
    AxisId.Caliper -> caliper.enabled
    AxisId.Tachometer -> tachometer.enabled
}

/** The button modes that an axis offers: "Divide circle" only on the angular axis. */
fun buttonModesFor(axis: AxisId): List<AxisButtonMode> =
    if (axis == AxisId.Angular) AxisButtonMode.entries else AxisButtonMode.entries.filter { mode -> mode != AxisButtonMode.DivideCircle }

/** The angle references of the auxiliary readout of a lathe: 0, 90, and 180 degrees. */
val ANGLE_REFERENCES: List<Int> = listOf(0, 90, 180)

/**
 * The check of the edge finder sample count of a probe profile:
 * a whole number from 1 to 10. The probe profile screen uses it. A refusal has the text "Number of
 * edge finder samples must be a number between 1 and 10 (inclusive)".
 */
object SampleCountField {

    /** Checks the text [text]. */
    fun check(text: String): SettingsResult<Int> = AxisSettingsRules.setEdgeFinderSampleCount(text.trim().toIntOrNull())

    /** Gives the message of a refused text, or null for a valid count. */
    fun message(text: String): UiText? = (check(text) as? SettingsResult.Refused)?.message?.toUiText()
}

/**
 * The text form of the spindle speed presets (`tach_presets`): numbers
 * separated by spaces, for example `105 160 250 585 898 1400`.
 */
object PresetsText {

    /** Gives the text of [presets]. A whole number has no decimal mark. */
    fun format(presets: List<Double>): String = presets.joinToString(" ") { value -> number(value) }

    /** Gives the text of one number: `105`, `12.5`. */
    fun number(value: Double): String {
        val whole = value.toLong()
        return if (whole.toDouble() == value) whole.toString() else value.toString()
    }

    /**
     * Gives the numbers of [text], or null when a part is not a number of 0 or more. The parts are
     * separated by spaces. A decimal comma is accepted. An empty text gives an empty list.
     */
    fun parse(text: String): List<Double>? {
        val parts = text.trim().split(Regex("\\s+")).filter { part -> part.isNotEmpty() }
        val values = parts.map { part -> part.replace(',', '.').toDoubleOrNull() ?: return null }
        if (values.any { value -> !value.isFinite() || value < 0.0 }) return null
        return values
    }
}
