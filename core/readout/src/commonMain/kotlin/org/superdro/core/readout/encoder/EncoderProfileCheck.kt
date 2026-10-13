package org.superdro.core.readout.encoder

import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText

/** One problem of an encoder profile. */
sealed interface EncoderProfileProblem {

    /** The text of the problem for the UI. */
    val message: ReadoutMessage

    /** A problem of the error map. */
    data class ErrorMap(val problem: MapProblem) : EncoderProfileProblem {
        override val message: ReadoutMessage get() = problem.message
    }

    /** A problem of the backlash map. */
    data class BacklashMap(val problem: MapProblem) : EncoderProfileProblem {
        override val message: ReadoutMessage get() = problem.message
    }

    /** The mode [BacklashMode.Map] on an encoder type that is not a leadscrew encoder. */
    data object BacklashMapNotAllowed : EncoderProfileProblem {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.BacklashMapNotForThisEncoder)
    }
}

/**
 * An encoder profile after the map checks.
 *
 * @property profile The profile that the input stage uses: a map that fails the check is empty,
 *   so it gives no correction.
 * @property problems The problems of the stored profile. An empty list means that the profile is
 *   valid.
 */
data class CheckedEncoderProfile(val profile: EncoderProfile, val problems: List<EncoderProfileProblem>) {
    /** True when the error map failed the check and gives no correction. */
    val errorMapRemoved: Boolean get() = problems.any { problem -> problem is EncoderProfileProblem.ErrorMap }

    /** True when the backlash map failed the check and gives no correction. */
    val backlashMapRemoved: Boolean get() = problems.any { problem -> problem is EncoderProfileProblem.BacklashMap }
}

/**
 * The validation of an encoder profile at each import and at each load. An empty list means that
 * the profile is valid.
 */
object EncoderProfileCheck {

    /**
     * Gives [profile] after the map checks. An error map with a problem and a
     * backlash map with a problem give no correction: the checked profile has an empty map.
     */
    fun checked(profile: EncoderProfile): CheckedEncoderProfile {
        val problems = problems(profile)
        val errorFails = problems.any { problem -> problem is EncoderProfileProblem.ErrorMap }
        val backlashFails = problems.any { problem -> problem is EncoderProfileProblem.BacklashMap }
        val used = if (errorFails || backlashFails) {
            profile.copy(
                errorMap = if (errorFails) emptyList() else profile.errorMap,
                backlashMap = if (backlashFails) emptyList() else profile.backlashMap,
            )
        } else {
            profile
        }
        return CheckedEncoderProfile(used, problems)
    }

    /** Gives the problems of [profile]. */
    fun problems(profile: EncoderProfile): List<EncoderProfileProblem> {
        val problems = ArrayList<EncoderProfileProblem>()
        MapValidation.errorMapProblems(profile.errorMap).forEach { problem -> problems.add(EncoderProfileProblem.ErrorMap(problem)) }
        if (profile.backlashMode == BacklashMode.Map && profile.type != EncoderType.LeadscrewEncoder) {
            problems.add(EncoderProfileProblem.BacklashMapNotAllowed)
        }
        MapValidation.backlashMapProblems(profile.backlashMap, profile.countsPerScrewRev)
            .forEach { problem -> problems.add(EncoderProfileProblem.BacklashMap(problem)) }
        return problems
    }
}
