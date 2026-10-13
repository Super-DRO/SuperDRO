package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The thread form. */
@Serializable
enum class ThreadForm { IsoMetricCoarse, IsoMetricFine, Unc, Unf, Unef, CustomMetric, CustomImperial }

/** The tolerance class of a thread. */
@Serializable
enum class ThreadClass { Class6g, Class6H, Class2A, Class3A, Class2B, Class3B }

/** The form of the threading cutter. */
@Serializable
enum class ThreadCutter { SharpV, FullForm, Partial }

/**
 * A lathe thread of a workspace. All lengths are µm.
 *
 * @property size The designation, for example `M10 × 1.5` or `1/4-20`.
 * @property preOpDiameterUm The pre-operation diameter (turn to).
 * @property cutToDiameterUm The cut-to diameter.
 * @property leftReliefUm W₁, or null.
 * @property rightReliefUm W₂, or null.
 * @property cutterTipRadiusUm The tip radius of a Partial cutter, or null when it is not known.
 *  The other cutters do not use it.
 */
@Serializable
data class ThreadSpec(
    val id: String = newId(),
    val workspaceId: String = "",
    val tag: String = "",
    val form: ThreadForm = ThreadForm.IsoMetricCoarse,
    val size: String = "",
    val threadClass: ThreadClass = ThreadClass.Class6g,
    val internal: Boolean = false,
    val cutter: ThreadCutter = ThreadCutter.SharpV,
    val pitchUm: Double = 0.0,
    val majorDiameterUm: Double = 0.0,
    val minorDiameterUm: Double = 0.0,
    val pitchDiameterUm: Double = 0.0,
    val preOpDiameterUm: Double = 0.0,
    val cutToDiameterUm: Double = 0.0,
    val startZUm: Double = 0.0,
    val stopZUm: Double = 0.0,
    val leftReliefUm: Double? = null,
    val rightReliefUm: Double? = null,
    val cutterTipRadiusUm: Double? = null,
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
)
