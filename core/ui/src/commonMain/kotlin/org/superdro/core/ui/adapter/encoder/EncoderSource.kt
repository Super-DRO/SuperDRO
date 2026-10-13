package org.superdro.core.ui.adapter.encoder

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.store.files.FileTimes
import org.superdro.core.ui.adapter.AdapterUiDeps

/**
 * The source of the encoder profile of a screen of the encoder area: the connected adapter of a tab
 * ("online"), or a stored adapter profile with no
 * link ("offline").
 */
sealed interface EncoderSource {

    /** The adapter profile that the machine profile of the tab [deviceId] selects. */
    data class Online(val deviceId: DeviceId) : EncoderSource

    /** The stored adapter profile [adapterProfileId]. The screen sends no byte to an adapter. */
    data class Offline(val adapterProfileId: String) : EncoderSource

    /** True for [Online]. Only an online screen can start the calibration and the wizard. */
    val online: Boolean get() = this is Online
}

/** The adapter profile of [source], with all its maps, or null when it is not there. */
fun AdapterUiDeps.adapterOf(source: EncoderSource): Flow<AdapterProfile?> = when (source) {
    is EncoderSource.Online -> tabAdapter(source.deviceId)
    is EncoderSource.Offline -> adapters.adapterProfile(source.adapterProfileId)
}

/**
 * Gives the encoder profile of [input] in [adapter]. An input with no profile gets the default
 * profile of the input (`EncoderProfile.defaultFor`).
 */
fun encoderOf(adapter: AdapterProfile, input: Char): EncoderProfile {
    val letter = Channels.normalize(input)
    return adapter.encoders[letter] ?: EncoderProfile.defaultFor(letter)
}

/** The texts of the numbers and the dates of the encoder area. */
object EncoderFormat {

    /**
     * Gives the text of [value] with at most [decimals] decimals and no zeros at the end, for
     * example 25400.0 gives "25400" and 5333.865 gives "5333.865". The decimal mark is `.`.
     */
    fun number(value: Double, decimals: Int = 3): String {
        if (!value.isFinite()) return value.toString()
        val factor = (1..decimals).fold(1L) { acc, _ -> acc * 10L }
        val scaled = kotlin.math.round(value * factor).toLong()
        val negative = scaled < 0
        val magnitude = kotlin.math.abs(scaled)
        val whole = magnitude / factor
        val fraction = (magnitude % factor).toString().padStart(decimals, '0').trimEnd('0')
        val sign = if (negative && (whole != 0L || fraction.isNotEmpty())) "-" else ""
        return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
    }

    /** Gives the text of [value] with exactly [decimals] decimals, for example 1.25 with 1 gives "1.3". */
    fun fixed(value: Double, decimals: Int): String {
        if (!value.isFinite()) return value.toString()
        val factor = (1..decimals).fold(1L) { acc, _ -> acc * 10L }
        val scaled = kotlin.math.round(value * factor).toLong()
        val negative = scaled < 0
        val magnitude = kotlin.math.abs(scaled)
        val whole = magnitude / factor
        val sign = if (negative && magnitude != 0L) "-" else ""
        if (decimals == 0) return "$sign$whole"
        val fraction = (magnitude % factor).toString().padStart(decimals, '0')
        return "$sign$whole.$fraction"
    }

    /** The local date and time `yyyy-MM-dd HH:mm` of [instant]. */
    fun dateTime(instant: Instant, timeZone: TimeZone): String =
        FileTimes.format(instant.toLocalDateTime(timeZone)).take(DATE_TIME_LENGTH)

    /** The local date and time `yyyy-MM-dd HH:mm:ss` of [instant]. */
    fun dateTimeSeconds(instant: Instant, timeZone: TimeZone): String = FileTimes.format(instant.toLocalDateTime(timeZone))

    private const val DATE_TIME_LENGTH = 16
}
