package org.superdro.core.functions.patterns

/**
 * The angle list of the arbitrary hole circle. The list keeps the
 * order of entry. The first angle is the first hole. Angles are arc seconds.
 */
data class HoleAngleList(val anglesArcSec: List<Double> = emptyList()) {

    /** The number of holes. */
    val size: Int get() = anglesArcSec.size

    /**
     * Adds an angle at the end of the list. The angle is normalized and rounded to 4 decimals of a
     * degree (`round(a × 10000) / 10000`). The list ignores a duplicate angle, with no message.
     */
    fun add(arcSec: Double): HoleAngleList {
        val angle = PatternAngles.roundToFourDecimals(PatternAngles.normalize(arcSec))
        if (anglesArcSec.any { existing -> PatternAngles.sameListAngle(existing, angle) }) return this
        return HoleAngleList(anglesArcSec + angle)
    }

    /**
     * Checks the angle field and adds the angle. The field accepts −360° to 360°. An error leaves
     * the list as it is.
     */
    fun addField(arcSec: Double?): AngleEntryResult {
        val checks = FieldChecks()
        val angle = checks.number(
            PatternField.HoleAngle, arcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC,
        )
        return if (angle == null) AngleEntryResult(this, checks.errors.single()) else AngleEntryResult(add(angle), null)
    }

    /** Removes the angle at [index]. */
    fun removeAt(index: Int): HoleAngleList = HoleAngleList(anglesArcSec.filterIndexed { position, _ -> position != index })
}

/** The result of [HoleAngleList.addField]: the new list, and the error of the field or null. */
data class AngleEntryResult(val list: HoleAngleList, val error: PatternError?)

/**
 * The arbitrary hole circle. SuperDRO has one mode.
 */
object ArbitraryHoleCircle {
    const val MIN_ANGLES: Int = 2

    /** The dialog title "Arbitrary Hole Circle". */
    fun title() = PatternTexts.TITLE_ARBITRARY.message()

    /**
     * Checks the inputs and gives the group (default prefix "Circle"). The list needs 2 or more
     * angles ("At least two hole angles are required.").
     */
    fun create(common: PatternCommon, radiusUm: Double?, angles: HoleAngleList): PatternResult {
        val checks = FieldChecks()
        val prefix = checks.prefix(common.prefix, PatternPrefix.DEFAULT_CIRCLE)
        val radius = checks.number(PatternField.CircleRadius, radiusUm, min = HoleCircle.MIN_RADIUS_UM)
        if (angles.size < MIN_ANGLES) checks.add(PatternError(PatternField.HoleAngle, PatternTexts.TWO_ANGLES.message()))
        if (checks.errors.isNotEmpty()) return PatternResult.Failure(checks.errors)
        val convention = common.effectiveConvention
        val points = angles.anglesArcSec.map { angle -> PatternAngles.circlePoint(radius!!, angle, convention) }
        return PatternResult.Success(group(common, prefix!!, points))
    }
}
