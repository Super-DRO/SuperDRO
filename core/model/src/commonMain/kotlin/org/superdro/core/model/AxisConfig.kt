package org.superdro.core.model

import kotlinx.serialization.Serializable

/**
 * The output axes of a machine profile. The workspace keeps one
 * [AxisState] for each value.
 */
@Serializable
enum class AxisId { X, Y, Z, Angular, Caliper, Tachometer }

/**
 * One adapter input of an axis.
 *
 * The scale values (resolution, hysteresis, filter, encoder direction) are in the
 * [EncoderProfile] of the adapter input, not here.
 *
 * @property channel The lowercase wire letter ([Channels]).
 * @property sign +1, or −1 for the axis invert.
 * @property weight The weight in the sum of the axis inputs. The projections change it.
 */
@Serializable
data class InputConfig(
    val channel: Char = Channels.X,
    val sign: Int = 1,
    val weight: Double = 1.0,
)

/**
 * The function of an axis action button. The ordinal is the value of
 * `<axis>_button_<n>_mode` in a TouchDRO settings file.
 */
@Serializable
enum class AxisButtonMode {
    /** 0: tap zero, long press absolute zero. */
    SetZero,

    /** 1: ABS or INC. */
    ToggleIncremental,

    /** 2: inch or mm; degrees or divisions on the angular axis. */
    ToggleUnits,

    /** 3: half (center). */
    SetCenter,

    /** 4: divisions sheet, only on the angular axis. */
    DivideCircle,

    /** 5: hold, then set. */
    Hold,
}

/**
 * One action button of an axis row. With the side panel open (the collapsed readout), only the
 * buttons with [showCollapsed] show.
 */
@Serializable
data class AxisButtonConfig(
    val mode: AxisButtonMode = AxisButtonMode.SetZero,
    val showExpanded: Boolean = true,
    val showCollapsed: Boolean = true,
) {
    companion object {
        /**
         * The three buttons of a new axis: modes 0, 1, 2; show when expanded true, true, true;
         * show when collapsed true, false, false.
         */
        fun defaults(): List<AxisButtonConfig> = listOf(
            AxisButtonConfig(AxisButtonMode.SetZero, showExpanded = true, showCollapsed = true),
            AxisButtonConfig(AxisButtonMode.ToggleIncremental, showExpanded = true, showCollapsed = false),
            AxisButtonConfig(AxisButtonMode.ToggleUnits, showExpanded = true, showCollapsed = false),
        )
    }
}

/**
 * A linear axis X, Y, or Z. On a lathe, Y is the auxiliary readout.
 *
 * The parameter defaults are the X values. [MachineProfile] gives [forAxis] for each axis.
 *
 * @property secondary The secondary input, or null when the secondary input is off.
 */
@Serializable
data class LinearAxisConfig(
    val enabled: Boolean = true,
    val label: String = "X",
    val buttons: List<AxisButtonConfig> = AxisButtonConfig.defaults(),
    val primary: InputConfig = InputConfig(Channels.X),
    val secondary: InputConfig? = null,
) {
    companion object {
        /** The defaults for [axis] (X, Y, or Z). */
        fun forAxis(axis: AxisId): LinearAxisConfig {
            require(axis == AxisId.X || axis == AxisId.Y || axis == AxisId.Z) { "$axis is not a linear axis." }
            return LinearAxisConfig(label = axis.name, primary = InputConfig(Channels.defaultPrimary(axis)))
        }
    }
}

/** The angular axis. It has no secondary input. */
@Serializable
data class AngularAxisConfig(
    val enabled: Boolean = false,
    val label: String = DEFAULT_LABEL,
    val buttons: List<AxisButtonConfig> = AxisButtonConfig.defaults(),
    val primary: InputConfig = InputConfig(Channels.W),
) {
    companion object {
        const val DEFAULT_LABEL: String = "α"
    }
}

/** The caliper or micrometer input of a lathe. It has its own offsets. */
@Serializable
data class CaliperConfig(
    val enabled: Boolean = false,
    val input: InputConfig = InputConfig(Channels.W),
)

/** The source of the tachometer value (`tach_mode`). */
@Serializable
enum class TachometerMode {
    /** 0: the RPM sensor on channel `t`. */
    Sensor,

    /** 1: the user selects a value from the preset list. */
    Preset,

    /** 2: the user types the value. */
    Direct,
}

/**
 * The tachometer. The pulses for each revolution are not here: they are
 * the resolution of the encoder profile of the input `t`.
 * An older JSON text with the key `ppu` still loads: the store ignores the key.
 */
@Serializable
data class TachometerConfig(
    val enabled: Boolean = false,
    val label: String = DEFAULT_LABEL,
    val mode: TachometerMode = TachometerMode.Sensor,
    val presets: List<Double> = DEFAULT_PRESETS,
    val invert: Boolean = false,
) {
    companion object {
        const val DEFAULT_LABEL: String = "RPM"

        /** `105 160 250 585 898 1400`. The preset sheet adds 0 first. */
        val DEFAULT_PRESETS: List<Double> = listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0)
    }
}

/**
 * The probe of a machine profile. The contact type and the debounce are
 * in the [AdapterProfile]. The offsets, the sample count, and the spread limit are in the
 * [ProbeProfile].
 */
@Serializable
data class ProbeConfig(
    val enabled: Boolean = false,
    val channel: Char = Channels.P,
    val edgeFinderEnabled: Boolean = true,
    val toolSetterEnabled: Boolean = false,
)
