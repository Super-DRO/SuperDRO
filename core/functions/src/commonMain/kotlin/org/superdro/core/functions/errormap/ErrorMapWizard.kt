package org.superdro.core.functions.errormap

/** The pass slot that records now: one of the passes, or the verification pass. */
sealed interface WizardSlot {
    /** "Pass 1" to "Pass 5": [index] 0 to 4. */
    data class Pass(val index: Int) : WizardSlot

    /** "Verification". */
    data object Verification : WizardSlot
}

/**
 * The state of the error map wizard of one target input. The wizard compares the target
 * input with a reference input on the same
 * adapter. The error is `reference − target`, in counts.
 *
 * - The user records up to [MAX_PASSES] passes ("Pass 1" … "Pass 5") and, after the passes, one
 *   verification pass.
 * - "Review Map" stops the pass that records now. It opens the report when at least 1 pass has
 *   samples ([canReview]).
 * - "Discard" deletes all passes ([discard]). "Exit" keeps the session file ([toSession]).
 *
 * This class has no clock and no coroutine. The presenter gives each pair with [offer].
 *
 * @param adapterId The id of the adapter profile.
 * @param input The letter of the target input.
 */
class ErrorMapWizard(
    val adapterId: String,
    val input: Char,
    referenceInput: Char? = null,
    passes: List<RecordedPass> = emptyList(),
    verification: RecordedPass? = null,
    private val maxPairs: Int = PassRecorder.MAX_PAIRS,
) {
    private val passList = passes.take(MAX_PASSES).toMutableList()
    private var verificationPass: RecordedPass? = verification
    private var recorder: PassRecorder? = null

    /** The reference input that the user selected, or null. */
    var referenceInput: Char? = referenceInput
        private set

    /** The slot that records now, or null. */
    var recording: WizardSlot? = null
        private set

    /** The recorded passes, in order. */
    val passes: List<RecordedPass> get() = passList.toList()

    /** The verification pass, or null. */
    val verification: RecordedPass? get() = verificationPass

    /**
     * Selects the reference input. The list of the UI has all inputs, except the target, that
     * sent data. Gives false for the target input itself or while a pass records. A change to a
     * different reference deletes the recorded passes, because their pairs use the old
     * reference.
     */
    fun selectReference(reference: Char): Boolean {
        if (reference.lowercaseChar() == input.lowercaseChar() || recording != null) return false
        if (reference != referenceInput && (passList.isNotEmpty() || verificationPass != null)) {
            passList.clear()
            verificationPass = null
        }
        referenceInput = reference
        return true
    }

    /** True when the user can start the next pass: a reference is set, nothing records, and fewer than 5 passes exist. */
    val canStartPass: Boolean get() = referenceInput != null && recording == null && passList.size < MAX_PASSES

    /** True when the user can start the verification pass: at least 1 pass has samples and nothing records. */
    val canStartVerification: Boolean get() =
        referenceInput != null && recording == null && passList.any { !it.isEmpty }

    /** Starts the next pass ("Start"). Gives the slot, or null when [canStartPass] is false. */
    fun startPass(): WizardSlot? {
        if (!canStartPass) return null
        val slot = WizardSlot.Pass(passList.size)
        recorder = PassRecorder(maxPairs)
        recording = slot
        return slot
    }

    /** Starts the verification pass. A new verification pass replaces the old one. */
    fun startVerification(): WizardSlot? {
        if (!canStartVerification) return null
        recorder = PassRecorder(maxPairs)
        recording = WizardSlot.Verification
        return WizardSlot.Verification
    }

    /**
     * Gives one pair of the target and the reference with the same time stamp to the pass that
     * records. Gives false when nothing records or the recorder refuses the pair.
     */
    fun offer(target: Long, reference: Long, timeMs: Long): Boolean =
        recorder?.offer(target, reference, timeMs) ?: false

    /** Gives the pass that records now, or null. */
    fun currentPass(): RecordedPass? = recorder?.snapshot()

    /**
     * Stops the pass that records ("Stop"). Gives the recorded pass, or null. A pass with no
     * pair does not take a pass place.
     */
    fun stop(): RecordedPass? {
        val active = recorder ?: return null
        val pass = active.stop()
        when (val slot = recording) {
            is WizardSlot.Pass -> if (!pass.isEmpty) passList.add(slot.index.coerceAtMost(passList.size), pass)
            WizardSlot.Verification -> verificationPass = pass.takeIf { !it.isEmpty }
            null -> Unit
        }
        recorder = null
        recording = null
        return pass
    }

    /** Clears one pass. The next passes move one place up. */
    fun clearPass(index: Int) {
        if (recording != null) return
        if (index in passList.indices) passList.removeAt(index)
    }

    /** Clears the verification pass. */
    fun clearVerification() {
        if (recording == WizardSlot.Verification) return
        verificationPass = null
    }

    /** "Discard": deletes all passes and the verification pass (after a confirm dialog). */
    fun discard() {
        recorder = null
        recording = null
        passList.clear()
        verificationPass = null
    }

    /** True when at least 1 pass has samples. "Review Map" then opens the report. */
    val canReview: Boolean get() = passList.any { !it.isEmpty } || (recording is WizardSlot.Pass && (recorder?.size ?: 0) > 0)

    /** "Review Map": stops the pass that records now and gives the passes with samples, or null. */
    fun review(): List<RecordedPass>? {
        stop()
        val usable = passList.filter { !it.isEmpty }
        return usable.ifEmpty { null }
    }

    /** True when any pass reached the pair limit. */
    val anyTruncated: Boolean get() = passList.any { it.truncated } || verificationPass?.truncated == true

    /** The sum of the dropped glitches of all passes. */
    val glitchesDropped: Int get() = passList.sumOf { it.glitchesDropped } + (verificationPass?.glitchesDropped ?: 0)

    /** Gives the session file content of this wizard. A pass that records now is not in it. */
    fun toSession(): ErrorMapWizardSession = ErrorMapWizardSession.of(
        adapterId = adapterId,
        input = input,
        referenceInput = referenceInput,
        passes = passList,
        verification = verificationPass,
    )

    companion object {
        /** The maximum number of passes. */
        const val MAX_PASSES: Int = 5

        /** Makes a wizard from a stored session ("Recovery File Found", "Yes"). */
        fun fromSession(session: ErrorMapWizardSession): ErrorMapWizard = ErrorMapWizard(
            adapterId = session.adapterId,
            input = session.input.firstOrNull() ?: 'x',
            referenceInput = session.reference?.firstOrNull(),
            passes = session.passes.map { it.toPass() },
            verification = session.verification?.toPass(),
        )
    }
}
