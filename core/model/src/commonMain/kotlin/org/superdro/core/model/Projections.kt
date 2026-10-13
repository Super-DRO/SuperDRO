package org.superdro.core.model

import kotlinx.serialization.Serializable

/**
 * The compound slide projection of a lathe workspace. The compound input adds to X with the weight
 * `sin θ` and to Z with the weight `cos θ`.
 *
 * @property angleArcSec The compound slide angle on the dial of the slide.
 * @property applyX True when the projection adds to X ("Apply to Cross Slide").
 * @property applyZ True when the projection adds to Z ("Apply to Carriage").
 */
@Serializable
data class CompoundProjection(
    val active: Boolean = false,
    val angleArcSec: Double = 0.0,
    val applyX: Boolean = true,
    val applyZ: Boolean = true,
)

/**
 * The head tilt projection of a vertical mill workspace. The quill input adds to X with `−sin α`,
 * to Y with `−sin β`, and to Z with
 * `sqrt(max(0, 1 − sin²α − sin²β))`.
 *
 * @property tiltArcSec The tilt angle α (toward X).
 * @property nodArcSec The nod angle β (toward Y).
 */
@Serializable
data class HeadTilt(
    val active: Boolean = false,
    val tiltArcSec: Double = 0.0,
    val nodArcSec: Double = 0.0,
    val applyX: Boolean = true,
    val applyY: Boolean = true,
)
