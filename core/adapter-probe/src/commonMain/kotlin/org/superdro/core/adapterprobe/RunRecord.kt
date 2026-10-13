package org.superdro.core.adapterprobe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.InputChannel

/** The end state of a run. */
@Serializable
enum class RunStatus {
    /** No run. */
    Idle,

    /** The run goes on. */
    Running,

    /** The run ended with its summary. */
    Done,

    /** The user stopped the run. */
    Stopped,

    /** The run could not do the test, for example the link did not open. */
    Failed,
}

/**
 * The adapter values of a run for the report: the identity, the firmware, and the six
 * input modes.
 */
@Serializable
data class AdapterSnapshot(
    val model: String? = null,
    val name: String? = null,
    val version: String? = null,
    val build: String? = null,
    val date: Long? = null,
    val mac: String? = null,
    val uuid: String? = null,
    val features: Long? = null,
    val uptime: Long? = null,
    /** The current mode ID of each channel letter `x y z w u v`. */
    val modes: Map<String, Int> = emptyMap(),
) {
    /** True when the snapshot has no value. */
    val isEmpty: Boolean
        get() = model == null && name == null && version == null && build == null && date == null && mac == null && uuid == null &&
            features == null && modes.isEmpty()

    companion object {
        /** Gives the snapshot of the queue values. */
        fun of(values: AdapterValues): AdapterSnapshot = AdapterSnapshot(
            model = values.model.value,
            name = values.name.value,
            version = values.version.value,
            build = values.build.value,
            date = values.date.value,
            mac = values.mac.value,
            uuid = values.uuid.value,
            features = values.features.value,
            uptime = values.uptime.value,
            modes = InputChannel.entries.mapNotNull { channel ->
                values.modes.getValue(channel).value?.let { modes -> channel.letter.toString() to modes.currentId }
            }.toMap(),
        )
    }
}

/**
 * One run of a test for the report.
 *
 * @property test The test ID, for example `T03`.
 * @property linkKind The link name: `usb`, `bt`, `tcp`, or `fake`.
 * @property link The text of the link.
 * @property linkKey The key of the link (see [LinkSpec.key]).
 * @property startedAtEpochMs The start time in ms since 1970.
 * @property status The end state.
 * @property summary The summary lines.
 * @property logFile The `.log` file name, or null.
 * @property binFile The `.bin` file name, or null.
 * @property adapter The adapter values that the run got.
 * @property label The label of the run, or null.
 * @property files The other files of the run, for example the logs of the T01 runs of TT1.
 * @property btAddress The Bluetooth address that the user typed in the test options for the
 *   MAC + 2 check (T02, Q51), or null.
 */
@Serializable
data class RunRecord(
    val test: String,
    val linkKind: String,
    val link: String,
    val linkKey: String,
    val startedAtEpochMs: Long,
    val status: RunStatus,
    val summary: List<String>,
    val logFile: String? = null,
    val binFile: String? = null,
    val adapter: AdapterSnapshot = AdapterSnapshot(),
    val label: String? = null,
    val files: List<String> = emptyList(),
    val btAddress: String? = null,
) {
    /** The test of the record, or null for an unknown ID. */
    val testId: ProbeTestId? get() = ProbeTestId.entries.firstOrNull { it.name == test }

    /**
     * The short result of the run: the Tests screen and the column "Result (short)" of table 8.1
     * show it. It is the first answer line of a question (`Q<n>: ` and a text), else the first
     * summary line. A table header (for example `Q41:  key   start ...`) and a fixed note of a
     * test (for example the note about the RSA-3072 signature of T16) are not a result.
     */
    val shortResult: String?
        get() = summary.firstOrNull { line -> RESULT_LINE.matches(line) && FIXED_NOTES.none { line.startsWith(it) } } ?: summary.firstOrNull()

    /** The record as one line of JSON (the file `runs.jsonl`). */
    fun toJsonLine(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** An answer line: a question ID, a colon, one space, and a text. */
        private val RESULT_LINE = Regex("^Q\\d+: \\S.*")

        /** The summary lines that each run of a test has, whatever the result. */
        private val FIXED_NOTES = listOf(
            "Q13: an RSA-3072 signature has 512 Base64 characters.",
            "Q13: if the stream stops about 1000 ms after the ack,",
            "Q40: see the line 'Pair ",
        )

        /** Reads one line of the file `runs.jsonl`. A line that is not valid gives null. */
        fun fromJsonLine(line: String): RunRecord? =
            if (line.isBlank()) null else runCatching { json.decodeFromString(serializer(), line) }.getOrNull()
    }
}
