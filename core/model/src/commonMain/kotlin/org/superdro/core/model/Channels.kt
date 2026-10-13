package org.superdro.core.model

/**
 * The input channels of an adapter.
 *
 * The wire letters are not case-sensitive (`X` and `x` are the same channel). SuperDRO stores
 * each channel letter as a lowercase `Char`.
 *
 * - The user can select `x y z w u v` for a linear axis, the angular axis, and the caliper.
 * - The user can select `p q` for the probe.
 * - The tachometer uses `t`.
 * - The parser accepts `a` to `f`, `r`, and `s` for wire compatibility and ignores their values.
 *  The machine setup does not offer them.
 */
object Channels {
    const val X: Char = 'x'
    const val Y: Char = 'y'
    const val Z: Char = 'z'
    const val W: Char = 'w'
    const val U: Char = 'u'
    const val V: Char = 'v'
    const val P: Char = 'p'
    const val Q: Char = 'q'
    const val T: Char = 't'

    /** The channels that a user can select for a linear axis, the angular axis, or the caliper. */
    val axisChannels: List<Char> = listOf(X, Y, Z, W, U, V)

    /** The probe ports. */
    val probeChannels: List<Char> = listOf(P, Q)

    /** The channel of the tachometer. */
    const val TACHOMETER: Char = T

    /** The letters that the parser accepts and ignores. */
    val ignoredChannels: List<Char> = listOf('a', 'b', 'c', 'd', 'e', 'f', 'r', 's')

    /** The adapter inputs that have an encoder profile. */
    val encoderInputs: List<Char> = listOf(X, Y, Z, W, U, V, T)

    /** The secondary channel that the setup suggests for X, Y, and Z. */
    const val DEFAULT_SECONDARY: Char = W

    /** The default primary channel of each output axis. */
    fun defaultPrimary(axis: AxisId): Char = when (axis) {
        AxisId.X -> X
        AxisId.Y -> Y
        AxisId.Z -> Z
        AxisId.Angular -> W
        AxisId.Caliper -> W
        AxisId.Tachometer -> T
    }

    /** The default secondary channel of each output axis, or null for no secondary input. */
    fun defaultSecondary(axis: AxisId): Char? = when (axis) {
        AxisId.X, AxisId.Y, AxisId.Z -> DEFAULT_SECONDARY
        AxisId.Angular, AxisId.Caliper, AxisId.Tachometer -> null
    }

    /** Gives the lowercase form of a wire letter. */
    fun normalize(letter: Char): Char = letter.lowercaseChar()

    /** True when the user can select [letter] for an axis. */
    fun isAxisChannel(letter: Char): Boolean = normalize(letter) in axisChannels

    /** True when [letter] is a probe port. */
    fun isProbeChannel(letter: Char): Boolean = normalize(letter) in probeChannels

    /** True when the parser accepts [letter] and the session ignores its value. */
    fun isIgnored(letter: Char): Boolean = normalize(letter) in ignoredChannels
}
