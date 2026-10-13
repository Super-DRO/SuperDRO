package org.superdro.core.protocol

/**
 * The six input channels of a two-way adapter.
 *
 * @property letter The lower case wire letter. The commands use it.
 * @property uiName The English name. The UI shows its own localized string from `core:resources`.
 * @property index The bit of the channel in the "Dirty Axes" mask: X 0, Y 1, Z 2, W 3, U 4,
 *   V 5.
 * @property referenceSubsystem The subsystem of the reference status, or null when the channel
 *   has no reference search.
 * @property clearedCode The status code "<letter> Axis Cleared".
 */
enum class InputChannel(
    val letter: Char,
    val uiName: String,
    val index: Int,
    val referenceSubsystem: Int?,
    val clearedCode: Int,
) {
    X('x', "Input X", 0, Subsystem.X_AXIS_REFERENCE, StatusCode.X_AXIS_CLEARED),
    Y('y', "Input Y", 1, Subsystem.Y_AXIS_REFERENCE, StatusCode.Y_AXIS_CLEARED),
    Z('z', "Input Z", 2, Subsystem.Z_AXIS_REFERENCE, StatusCode.Z_AXIS_CLEARED),
    W('w', "Input W", 3, Subsystem.W_AXIS_REFERENCE, StatusCode.W_AXIS_CLEARED),
    U('u', "Aux 1", 4, null, StatusCode.U_AXIS_CLEARED),
    V('v', "Aux 2", 5, null, StatusCode.V_AXIS_CLEARED),
    ;

    /** True when the adapter can find a reference mark on this channel: `x y z w`. */
    val referenceAllowed: Boolean get() = referenceSubsystem != null

    companion object {
        /** The bits of the six channels in the "Dirty Axes" mask. */
        const val DIRTY_MASK: Int = 0x3F

        /** Gives the channel of a lower case wire letter, or null. An upper case letter gives null. */
        fun forLetter(letter: Char): InputChannel? = entries.firstOrNull { it.letter == letter }

        /** Gives the channel of a reference subsystem (0x77 to 0x7A), or null. */
        fun forReferenceSubsystem(subsystem: Int): InputChannel? =
            entries.firstOrNull { it.referenceSubsystem == subsystem }

        /**
         * Gives the dirty channels of a "Dirty Axes" status code: bit `n` is the channel
         * with the index `n`. Example: `$3205;` gives X and Z. The bits 6 and up have no channel.
         */
        fun dirtyChannels(mask: Int): Set<InputChannel> =
            entries.filterTo(LinkedHashSet()) { mask and (1 shl it.index) != 0 }
    }
}

/** The raw value view of an input mode in the Adapter Details screen. */
enum class RawView {
    /** The probe form `p: <0|1>, q: <0|1>`. */
    ProbeForm,

    /** The count of the channel letter. */
    ChannelCount,

    /** The value of `T`. */
    TachometerValue,
}

/**
 * The input modes of a channel.
 *
 * The ID is decimal in `#set_mode` and 4 hex digits in the `#get_mode` answer.
 *
 * @property id The mode ID. [Unknown] has the ID 0. It is never on the wire.
 * @property text The English name. The UI shows its own localized string.
 * @property rawView The raw value view, or null for [Unknown].
 */
enum class InputMode(val id: Int, val text: String, val rawView: RawView?) {
    Disabled(1, "Disabled", RawView.ProbeForm),
    QuadratureAB(2, "Quadrature A/B", RawView.ChannelCount),
    Tachometer(4, "Tachometer", RawView.TachometerValue),
    DigitalProbe(8, "Digital Probe", RawView.ProbeForm),
    LimitXY(16, "Limit (X,Y)", RawView.ProbeForm),
    LimitZW(32, "Limit (Z,W)", RawView.ProbeForm),
    BinarySwitch(64, "Binary Switch", RawView.ProbeForm),
    Bcd7(128, "BCD-7", RawView.ProbeForm),
    Bin6(256, "BIN6", RawView.ChannelCount),
    IGaging21Bit(512, "iGaging 21 Bit", RawView.ProbeForm),
    IGagingSpc(1024, "iGaging SPC", RawView.ProbeForm),
    MitutoyoSpc(2048, "Mitutoyo SPC", RawView.ChannelCount),
    Unknown(0, "Unknown", null),
    ;

    /** The 4 lower case hex digits of the ID, as in the `#get_mode` answer. Example: `0002`. */
    val hexId: String get() = id.toString(16).padStart(4, '0')

    companion object {
        /** The modes of the wire, in the order of the entries. [Unknown] is not in the list. */
        val wireModes: List<InputMode> = entries.filter { it != Unknown }

        /** Gives the mode of an ID. An ID that is not exactly one mode gives [Unknown]. */
        fun forId(id: Int): InputMode = wireModes.firstOrNull { it.id == id } ?: Unknown

        /** Gives the modes whose ID has a bit in [mask] (`(id & mask) != 0`), in the order of the entries. */
        fun supported(mask: Int): List<InputMode> = wireModes.filter { it.id and mask != 0 }
    }
}

/**
 * The modes of one channel from a `#get_mode` answer.
 *
 * @property supportedMask The 4 hex digits of the supported mask.
 * @property currentId The 4 hex digits of the current mode.
 */
data class ChannelModes(val channel: InputChannel, val supportedMask: Int, val currentId: Int) {

    /** The supported modes, in the order of the [InputMode] entries. */
    val supported: List<InputMode> get() = InputMode.supported(supportedMask)

    /** The current mode. A value that is not exactly one mode ID gives [InputMode.Unknown]. */
    val current: InputMode get() = InputMode.forId(currentId)

    companion object {
        private val answer = Regex("[xyzwuv][0-9a-f]{8}")

        /**
         * Parses the value of a mode answer `?01<ch><mask4><mode4>;`. [value] is the text after
         * `?01`. The value must match `[xyzwuv][0-9a-f]{8}` (lower case only). Else the result is
         * null, and the caller ignores the answer.
         *
         * Example: `x000f0002` gives the channel X with the modes 1, 2, 4, and 8. The current mode
         * is 2 (Quadrature A/B).
         */
        fun parse(value: String): ChannelModes? {
            if (!answer.matches(value)) return null
            val channel = InputChannel.forLetter(value[0]) ?: return null
            return ChannelModes(channel, value.substring(1, 5).toInt(16), value.substring(5, 9).toInt(16))
        }

        /** The value before a `#get_mode`: the mode is "Unknown", and the supported list is empty. */
        fun unknown(channel: InputChannel): ChannelModes = ChannelModes(channel, 0, InputMode.Unknown.id)
    }
}

/**
 * The rules of the channel rows of the Adapter Details screen.
 */
object ChannelRules {

    /** The model prefix of the genuine adapters. The case does not matter. */
    const val REFERENCE_MODEL_PREFIX: String = "tda-"

    /** True when [model] starts with `tda-`. The case does not matter. */
    fun isReferenceModel(model: String?): Boolean =
        model != null && model.startsWith(REFERENCE_MODEL_PREFIX, ignoreCase = true)

    /**
     * True when the row shows "Reference". All these conditions must be true:
     * - The model starts with `tda-` (the case does not matter).
     * - The channel is `x`, `y`, `z`, or `w`.
     * - The current mode is Quadrature A/B (2).
     * - The mode did not change since the screen read it ([modeUnchanged]).
     *
     * Thus the button shows only when the command can go out.
     */
    fun showReference(model: String?, channel: InputChannel, current: InputMode, modeUnchanged: Boolean): Boolean =
        isReferenceModel(model) && channel.referenceAllowed && current == InputMode.QuadratureAB && modeUnchanged

    /** True when the row shows "Clear": the current mode is Quadrature A/B, and it did not change. */
    fun showClear(current: InputMode, modeUnchanged: Boolean): Boolean =
        current == InputMode.QuadratureAB && modeUnchanged

    /** The mode list of a row: only the modes in the supported mask, in the order of the [InputMode] entries. */
    fun modeList(modes: ChannelModes): List<InputMode> = modes.supported

    /** True when "Save" and "Cancel" show: the selection is different from the current mode. */
    fun showSave(selection: InputMode, current: InputMode): Boolean = selection != current
}
