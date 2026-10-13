package org.superdro.core.functions.errormap

import kotlinx.serialization.Serializable
import org.superdro.core.model.SuperDroJson

/**
 * One pass of the session file, in a column form: the pair `i` is
 * `(targets[i], references[i], timesMs[i])`.
 */
@Serializable
data class SessionPass(
    val targets: List<Long> = emptyList(),
    val references: List<Long> = emptyList(),
    val timesMs: List<Long> = emptyList(),
    val truncated: Boolean = false,
    val glitchesDropped: Int = 0,
) {
    /** True when the three columns have the same length. */
    val isConsistent: Boolean get() = targets.size == references.size && targets.size == timesMs.size

    /** Gives the recorded pass of this session pass. */
    fun toPass(): RecordedPass = RecordedPass(
        pairs = targets.indices.map { SamplePair(targets[it], references[it], timesMs[it]) },
        truncated = truncated,
        glitchesDropped = glitchesDropped,
    )

    companion object {
        /** Gives the session pass of [pass]. */
        fun of(pass: RecordedPass): SessionPass = SessionPass(
            targets = pass.pairs.map { it.target },
            references = pass.pairs.map { it.reference },
            timesMs = pass.pairs.map { it.timeMs },
            truncated = pass.truncated,
            glitchesDropped = pass.glitchesDropped,
        )
    }
}

/**
 * The content of the session file of the error map wizard. The file is
 * `<app files>/error_map_wizard/adapter_<adapterId>_<INPUT>.emw`. `core:store` writes and reads
 * the file. SuperDRO writes JSON with [SuperDroJson].
 *
 * - The wizard writes the file after each stop or clear.
 * - An empty session ([isEmpty]) deletes the file.
 * - "Save Map" deletes the file. "Exit" keeps it.
 *
 * @property format The format version, 1.
 * @property adapterId The id of the adapter profile.
 * @property input The letter of the target input.
 * @property reference The letter of the reference input, or null.
 * @property passes Pass 1 to pass 5.
 * @property verification The verification pass, or null.
 */
@Serializable
data class ErrorMapWizardSession(
    val format: Int = FORMAT,
    val adapterId: String = "",
    val input: String = "",
    val reference: String? = null,
    val passes: List<SessionPass> = emptyList(),
    val verification: SessionPass? = null,
) {
    /** True when the session has no pair. Then the store deletes the file. */
    val isEmpty: Boolean
        get() = passes.all { it.targets.isEmpty() } && (verification?.targets?.isEmpty() ?: true)

    /** The file name of this session. */
    val fileName: String get() = fileName(adapterId, input.firstOrNull() ?: 'x')

    /** Gives the JSON text of this session. */
    fun encode(): String = SuperDroJson.encodeToString(serializer(), this)

    companion object {
        /** The format version. */
        const val FORMAT: Int = 1

        /** The folder of the session files in the app files. */
        const val FOLDER: String = "error_map_wizard"

        /** The file name extension. */
        const val EXTENSION: String = "emw"

        /** Gives the file name `adapter_<adapterId>_<INPUT>.emw`. */
        fun fileName(adapterId: String, input: Char): String =
            "adapter_${adapterId}_${input.uppercaseChar()}.$EXTENSION"

        /** Gives the path `error_map_wizard/adapter_<adapterId>_<INPUT>.emw` in the app files. */
        fun relativePath(adapterId: String, input: Char): String = "$FOLDER/${fileName(adapterId, input)}"

        /** Makes a session from the wizard data. The input letters are stored in uppercase. */
        fun of(
            adapterId: String,
            input: Char,
            referenceInput: Char?,
            passes: List<RecordedPass>,
            verification: RecordedPass?,
        ): ErrorMapWizardSession = ErrorMapWizardSession(
            adapterId = adapterId,
            input = input.uppercaseChar().toString(),
            reference = referenceInput?.uppercaseChar()?.toString(),
            passes = passes.map { SessionPass.of(it) },
            verification = verification?.let { SessionPass.of(it) },
        )

        /**
         * Reads a session text. Gives null for a text that is not a session: bad JSON, another
         * format, no input, more than 5 passes, or columns of different lengths. The UI then
         * starts a new session.
         */
        fun decode(text: String): ErrorMapWizardSession? {
            val session = try {
                SuperDroJson.decodeFromString(serializer(), text)
            } catch (_: Exception) {
                return null
            }
            val valid = session.format == FORMAT &&
                session.input.length == 1 &&
                session.passes.size <= ErrorMapWizard.MAX_PASSES &&
                session.passes.all { it.isConsistent } &&
                (session.verification?.isConsistent ?: true)
            return if (valid) session else null
        }
    }
}
