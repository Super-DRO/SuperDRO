package org.superdro.core.ui.settings.logs

import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.ISeedling
import kotlin.jvm.Synchronized
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.util.RingBuffer

/** The level of a log line. Each level has a letter, so the log screen shows more than a color. */
enum class LogLevel(val letter: Char) {
    Debug('D'),
    Info('I'),
    Warning('W'),
    Error('E');

    companion object {
        /** Gives the level of an Arbor level, or null for `VERBOSE`. `WTF` is an error. */
        fun ofArbor(level: Int): LogLevel? = when (level) {
            Arbor.DEBUG -> Debug
            Arbor.INFO -> Info
            Arbor.WARNING -> Warning
            Arbor.ERROR, Arbor.WTF -> Error
            else -> null
        }
    }
}

/**
 * One line of the log.
 *
 * @property sequence The order of the line in all buffers: a later line has a larger number.
 * @property device The session of the line, or null for a line of the app.
 * @property text The message. A session line has no "<device>: " prefix here.
 * @property error The summary of the throwable of the line, or null.
 */
data class LogLine(
    val sequence: Long,
    val time: Instant,
    val level: LogLevel,
    val tag: String,
    val device: DeviceId?,
    val text: String,
    val error: String? = null,
)

/** The lines that the log screen shows: all lines, the lines of the app, or the lines of one session. */
sealed interface LogFilter {
    /** The lines of all buffers in their order. */
    data object All : LogFilter

    /** The lines that are not of a session. */
    data object App : LogFilter

    /** The lines of the session of [device]. */
    data class Machine(val device: DeviceId) : LogFilter
}

/**
 * The log sink of the log screen: an Arbor seedling that keeps the newest
 * lines in memory, one ring buffer for each session and one for the app.
 *
 * - A line with the tag [SESSION_TAG] and the text `"<deviceId>: <message>"` goes to the buffer of
 *   that device (the session logs of `core:connection`). Each other line goes to the app buffer.
 * - Each buffer keeps the newest [capacity] lines. A buffer with no space removes its oldest line.
 * - The sink keeps no `VERBOSE` line: these are the lines of each status message of the adapter.
 * - The sessions log from many threads, so each function is synchronized.
 * - [revision] increments at each new line and at [clear]. The log screen reads the lines again
 *   when it changes.
 *
 * The sink keeps the lines only while the process lives. It writes no file.
 *
 * @param clock Gives the time of each line. A test gives a fixed clock.
 * @param maxSessions The most session buffers. A new session over the limit removes the buffer
 *   that got its last line first.
 */
class SessionLogSink(
    private val clock: Clock = Clock.System,
    val capacity: Int = DEFAULT_CAPACITY,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) : ISeedling {

    override val tag: String get() = DEFAULT_TAG

    private var sequence = 0L
    private val app = RingBuffer<LogLine>(capacity)
    private val sessions = LinkedHashMap<DeviceId, RingBuffer<LogLine>>()

    private val mutableRevision = MutableStateFlow(0L)

    /** Increments at each new line and at each [clear]. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    override fun log(level: Int, tag: String, msg: String, throwable: Throwable?, args: Array<out Any?>?) {
        val lineLevel = LogLevel.ofArbor(level) ?: return
        add(lineLevel, tag, msg, throwable?.toString())
    }

    override fun log(level: Int, tag: String, msg: () -> String, throwable: Throwable?) {
        // A VERBOSE line does not make its text.
        val lineLevel = LogLevel.ofArbor(level) ?: return
        add(lineLevel, tag, msg(), throwable?.toString())
    }

    /** Adds one line. A session line goes to the buffer of its device. */
    @Synchronized
    fun add(level: LogLevel, tag: String, message: String, error: String? = null) {
        val device = deviceOf(tag, message)
        val text = if (device != null) message.substring(device.value.length + SEPARATOR.length) else message
        val line = LogLine(++sequence, clock.now(), level, tag, device, text, error)
        if (device == null) {
            app.add(line)
        } else {
            val buffer = sessions.remove(device) ?: RingBuffer(capacity)
            buffer.add(line)
            sessions[device] = buffer
            while (sessions.size > maxSessions) sessions.remove(sessions.keys.first())
        }
        mutableRevision.value = sequence
    }

    /** Gives the lines of [filter], oldest first. */
    @Synchronized
    fun lines(filter: LogFilter = LogFilter.All): List<LogLine> = when (filter) {
        LogFilter.App -> app.toList()
        is LogFilter.Machine -> sessions[filter.device]?.toList().orEmpty()
        LogFilter.All -> (app.toList() + sessions.values.flatMap { buffer -> buffer.toList() }).sortedBy { line -> line.sequence }
    }

    /** The devices that have a buffer, in the order of their last line. */
    @Synchronized
    fun devices(): List<DeviceId> = sessions.keys.toList()

    /** Removes all lines. */
    @Synchronized
    fun clear() {
        app.clear()
        sessions.clear()
        mutableRevision.value = ++sequence
    }

    companion object {
        /** The tag of the session logs of `core:connection` (`LinkSession`). */
        const val SESSION_TAG: String = "DeviceSession"

        /** The tag of a line with no tag of its own. */
        const val DEFAULT_TAG: String = "SuperDRO"

        /** The lines of each buffer. */
        const val DEFAULT_CAPACITY: Int = 2000

        /** The most session buffers. */
        const val DEFAULT_MAX_SESSIONS: Int = 32

        private const val SEPARATOR: String = ": "

        /** Gives the device of a session line, or null for a line of the app. */
        fun deviceOf(tag: String, message: String): DeviceId? {
            if (tag != SESSION_TAG) return null
            val end = message.indexOf(SEPARATOR)
            if (end <= 0) return null
            val id = message.substring(0, end)
            return if (id.any { character -> character.isWhitespace() }) null else DeviceId(id)
        }
    }
}
