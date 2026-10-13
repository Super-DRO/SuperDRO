package org.superdro.core.ui.adapter.details

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Channels
import org.superdro.core.model.DeclaredInput
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.ChannelModes
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.RawView
import org.superdro.core.readout.NumberPattern
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_command_cancelled
import org.superdro.core.resources.adapter_command_not_sent
import org.superdro.core.resources.adapter_failure_clear
import org.superdro.core.resources.adapter_failure_mode
import org.superdro.core.resources.adapter_failure_reference
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.text.UiText

/** The settings screen that the gear button of a channel row opens. */
sealed interface GearTarget {
    /** The encoder settings of the input [input]. */
    data class Encoder(val input: Char) : GearTarget

    /** The tachometer pulses. */
    data object Tach : GearTarget

    /** The probe input settings. */
    data object Probe : GearTarget
}

/**
 * The rules of the Adapter Details screen. The
 * functions are pure, so the tests can check each rule.
 */
object AdapterDetailsRules {

    /** A refresh interval of this time or more gives 0 Hz. */
    const val MAX_RATE_INTERVAL_MS: Long = 10_000L

    /** A refresh interval of less than this time gives 0 Hz. */
    const val MIN_RATE_INTERVAL_MS: Long = 1L

    /** The text of the refresh rate 0. */
    const val ZERO_RATE: String = "0 Hz"

    private val RATE = NumberPattern("0.00")

    /** The text of an information field: the value, or `???` when the value is not valid. */
    fun fieldText(value: String?): String = value?.takeIf { text -> text.isNotBlank() } ?: AdapterTexts.NOT_VALID

    /**
     * The firmware date: the seconds since 1970-01-01 UTC as `yyyy-MM-dd HH:mm` in the local time of
     * [timeZone], or `???`. Example: `67df4f00` (1742688000 s) gives "2025-03-23 00:00" in UTC.
     */
    fun dateText(seconds: Long?, timeZone: TimeZone): String {
        if (seconds == null) return AdapterTexts.NOT_VALID
        val local = try {
            Instant.fromEpochSeconds(seconds).toLocalDateTime(timeZone)
        } catch (_: IllegalArgumentException) {
            return AdapterTexts.NOT_VALID
        }
        return "${pad(local.year, 4)}-${pad(local.month.number, 2)}-${pad(local.day, 2)} ${pad(local.hour, 2)}:${pad(local.minute, 2)}"
    }

    /** The model: the adapter value, else the model of the adapter profile. */
    fun model(values: AdapterValues, profile: AdapterProfile?): String? = values.model.value ?: profile?.model

    /**
     * The "Bluetooth ID": the MAC + 2 with the carry, as 6 bytes of 2 hex digits joined by
     * `:`, else the identity of the adapter profile. Example: `?05246f28a1b2c3;` gives
     * "24:6f:28:a1:b2:c5".
     */
    fun bluetoothId(values: AdapterValues, profile: AdapterProfile?): String? = values.bluetoothId ?: profile?.identity

    /**
     * The refresh rate: `1000 / dt` Hz with 2 decimals, where [dtMs] is the time between the last two
     * values of the channel. 0 Hz when there is no interval, when `dt` < 1 ms, or when `dt` ≥ 10000 ms.
     * Examples: 20 ms gives "50.00 Hz", 3 ms gives "333.33 Hz".
     */
    fun refreshRate(dtMs: Long?): String {
        if (dtMs == null || dtMs < MIN_RATE_INTERVAL_MS || dtMs >= MAX_RATE_INTERVAL_MS) return ZERO_RATE
        return "${RATE.format(1000.0 / dtMs)} Hz"
    }

    /**
     * The interval of the refresh rate: the time between the last two values ([previousMs] and
     * [newestMs]). When no value came for [MAX_RATE_INTERVAL_MS] or more before [nowMs], the channel
     * stopped: the interval is the time since the last value.
     */
    fun interval(previousMs: Long?, newestMs: Long?, nowMs: Long): Long? {
        if (newestMs == null) return null
        val sinceLast = nowMs - newestMs
        if (sinceLast >= MAX_RATE_INTERVAL_MS) return sinceLast
        return previousMs?.let { previous -> newestMs - previous }
    }

    /** The raw value view of [mode]. A mode with no view ("Unknown") is one of the "other modes": the probe form. */
    fun rawView(mode: InputMode): RawView = mode.rawView ?: RawView.ProbeForm

    /** The probe form `p: <0|1>, q: <0|1>` of the probe states of [snapshot]. */
    fun probeForm(snapshot: InputSnapshot): String {
        fun bit(port: Char) = if (snapshot.probes[port] == true) 1 else 0
        return "p: ${bit(Channels.P)}, q: ${bit(Channels.Q)}"
    }

    /**
     * The raw value of a row: the count of the channel letter, the value of `T`, or
     * the probe form. A count that did not come yet shows `???`.
     */
    fun rawValue(view: RawView, channel: InputChannel, snapshot: InputSnapshot): String = when (view) {
        RawView.ChannelCount -> snapshot.channels[channel.letter]?.rawCount?.toString() ?: AdapterTexts.NOT_VALID
        RawView.TachometerValue -> snapshot.channels[Channels.T]?.rawCount?.toString() ?: AdapterTexts.NOT_VALID
        RawView.ProbeForm -> probeForm(snapshot)
    }

    /**
     * The input of the refresh rate of a row: the channel letter for a count, `t` for the value of
     * `T`, or null for the probe form ("N/A").
     */
    fun rateInput(view: RawView, channel: InputChannel): Char? = when (view) {
        RawView.ChannelCount -> channel.letter
        RawView.TachometerValue -> Channels.T
        RawView.ProbeForm -> null
    }

    /**
     * The settings screen of the gear button: the encoder settings for an encoder mode, the
     * tach pulses for the tachometer, the probe input for a digital probe. A switch, a limit, and a
     * disabled channel have no gear. A mode that is not known yet ("Unknown") opens the encoder
     * settings, so the user can set up an input of an adapter that sends no mode.
     */
    fun gear(channel: InputChannel, mode: InputMode): GearTarget? = when (mode) {
        InputMode.QuadratureAB, InputMode.Bcd7, InputMode.Bin6, InputMode.IGaging21Bit, InputMode.IGagingSpc,
        InputMode.MitutoyoSpc, InputMode.Unknown,
        -> GearTarget.Encoder(channel.letter)
        InputMode.Tachometer -> GearTarget.Tach
        InputMode.DigitalProbe -> GearTarget.Probe
        InputMode.Disabled, InputMode.LimitXY, InputMode.LimitZW, InputMode.BinarySwitch -> null
    }

    /** The declared input of a row of a legacy adapter: X to W, then "Probe" for `u` and "Tach" for `v`. */
    fun declaredInput(channel: InputChannel): DeclaredInput = when (channel) {
        InputChannel.X -> DeclaredInput.X
        InputChannel.Y -> DeclaredInput.Y
        InputChannel.Z -> DeclaredInput.Z
        InputChannel.W -> DeclaredInput.W
        InputChannel.U -> DeclaredInput.Probe
        InputChannel.V -> DeclaredInput.Tach
    }

    /** The raw value view of a declared-input row: counts for X to W, the probe form for "Probe", the value of `T` for "Tach". */
    fun declaredView(channel: InputChannel): RawView = when (channel) {
        InputChannel.U -> RawView.ProbeForm
        InputChannel.V -> RawView.TachometerValue
        else -> RawView.ChannelCount
    }

    /** The gear of a declared-input row: the encoder settings for X to W, the probe input, the tach pulses. */
    fun declaredGear(channel: InputChannel): GearTarget = when (channel) {
        InputChannel.U -> GearTarget.Probe
        InputChannel.V -> GearTarget.Tach
        else -> GearTarget.Encoder(channel.letter)
    }

    /**
     * True when the rows show the declared inputs: the adapter reported no mode, and the
     * adapter profile has declared inputs.
     */
    fun showDeclared(values: AdapterValues, profile: AdapterProfile?): Boolean =
        profile != null && profile.declaredInputs.isNotEmpty() && values.modes.values.none { value -> value.value != null }

    /** The modes of [channel], or the "Unknown" modes before the first answer. */
    fun modesOf(values: AdapterValues, channel: InputChannel): ChannelModes =
        values.modes[channel]?.value ?: ChannelModes.unknown(channel)

    /**
     * The declared inputs of a two-way adapter from its mode answers: X, Y, Z, or W when the
     * channel supports a mode other than Disabled; Probe when a
     * channel supports Digital Probe; Tach when a channel supports Tachometer. Null while a channel
     * has no answer.
     */
    fun declaredFromModes(values: AdapterValues): Set<DeclaredInput>? {
        val all = InputChannel.entries.map { channel -> values.modes[channel]?.value ?: return null }
        return buildSet {
            all.forEach { modes ->
                val supported = modes.supported
                if (modes.channel.referenceAllowed && supported.any { mode -> mode != InputMode.Disabled }) add(declaredInput(modes.channel))
                if (InputMode.DigitalProbe in supported) add(DeclaredInput.Probe)
                if (InputMode.Tachometer in supported) add(DeclaredInput.Tach)
            }
        }
    }

    /**
     * The failure text of a command result in the row of the channel, or null for a result with
     * no failure.
     *
     * - An adapter error: `Command failed: <text> (<sub>:<code>)`, for example
     *   "Command failed: Invalid argument (6:3)".
     * - No result in 10000 ms: the text of the command (mode write, reference, clear).
     */
    fun failureText(command: AdapterCommand, result: CommandResult): UiText? = when (result) {
        CommandResult.Completed -> null
        is CommandResult.Failed -> UiText.Plain(result.failure.message)
        CommandResult.NotConfirmed -> when (command) {
            is AdapterCommand.SetMode -> UiText.of(Res.string.adapter_failure_mode)
            is AdapterCommand.RefAxis -> UiText.of(Res.string.adapter_failure_reference)
            is AdapterCommand.ClearCounter -> UiText.of(Res.string.adapter_failure_clear)
            else -> UiText.of(Res.string.adapter_command_cancelled)
        }
        CommandResult.NotSent -> UiText.of(Res.string.adapter_command_not_sent)
        CommandResult.Cancelled -> UiText.of(Res.string.adapter_command_cancelled)
    }

    private fun pad(value: Int, length: Int): String = value.toString().padStart(length, '0')
}
