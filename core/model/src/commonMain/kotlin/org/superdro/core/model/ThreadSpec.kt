package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The thread form. */
@Serializable
enum class ThreadForm {
    IsoMetricCoarse,
    IsoMetricFine,
    Unc,
    Unf,
    Unef,
    Bsw,
    Bsf,
    Bspp,
    IsoTrapezoidal,
    Acme,
    Npt,
    Bspt,
    CustomMetric,
    CustomImperial,
}

/** The tolerance class of a thread. */
@Serializable
enum class ThreadClass {
    Class6g,
    Class6H,
    Class2A,
    Class3A,
    Class2B,
    Class3B,
    ClassA,
    ClassB,
    ClassMedium,
    Class7e,
    Class7H,
    Class2G,
    Class3G,
}

/** The form of the threading cutter. */
@Serializable
enum class ThreadCutter { SharpV, FullForm, Partial }

/** The infeed mode of a single-point thread cut. */
@Serializable
enum class ThreadInfeedMode { Radial, ModifiedFlank, Flank }

/** The tip radius preset of a partial threading insert. */
@Serializable
enum class PartialInsertPreset { Custom, A60, G60, AG60, N60 }

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
 * @property leftHand True for a left-hand thread, false for a right-hand thread.
 * @property starts The number of thread starts (1 or more).
 * @property infeedMode The compound infeed mode.
 * @property roughingPasses The manual roughing pass count, or null for the automatic count.
 * @property wireDiameterUm The custom three-wire diameter, or null for the best wire diameter.
 * @property insertPreset The partial insert preset.
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
    val leftHand: Boolean = false,
    val starts: Int = 1,
    val infeedMode: ThreadInfeedMode = ThreadInfeedMode.ModifiedFlank,
    val roughingPasses: Int? = null,
    val wireDiameterUm: Double? = null,
    val insertPreset: PartialInsertPreset = PartialInsertPreset.Custom,
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
)

