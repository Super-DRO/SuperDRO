package org.superdro.core.functions.transform

import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace

/** What a transform does with a guide that it cannot keep. */
enum class GuidePolicy {
    /** "Continue": delete the guide. */
    Delete,

    /** "Convert and Continue": the guide becomes a 2D point. */
    Convert,
}

/** The result of the guide map of a transform. */
sealed interface GuideMapResult {
    /** The guide stays a guide, with a new axis or value. */
    data class Kept(val guide: Guide) : GuideMapResult

    /** The transform deleted the guide. */
    data object Deleted : GuideMapResult

    /** The guide became a 2D point at ([xUm], [yUm]). */
    data class ConvertedToPoint(val xUm: Double, val yUm: Double) : GuideMapResult
}

/**
 * One workspace transform. It has a point map, a guide
 * map, a "breaks guides" flag, an image matrix, and a shape map. All positions are in the workspace
 * frame: the absolute positions without the Align Axes and Rotate Part frames. The
 * lengths are µm and the shapes are integer nm.
 */
interface WorkspaceTransform {

    /** True when the transform changes nothing. The pipeline then writes nothing. */
    val isIdentity: Boolean

    /** True when the transform cannot keep some guides on X or Y (a rotate that is not a multiple of 90°). */
    val breaksGuides: Boolean

    /**
     * True when Align Axes stays on after the transform (scale and translate). A flip or a
     * rotate turns Align Axes off.
     */
    val keepsAxisAlignment: Boolean

    /** The matrix that comes after the stored image matrix: `M_new = imageMatrix × M`. */
    val imageMatrix: Matrix3

    /** Gives the sub-datum after the transform. A coordinate that is absent stays absent. */
    fun mapPoint(subDatum: SubDatum): SubDatum

    /** Gives the guide after the transform with [policy] for a guide that the transform cannot keep. */
    fun mapGuide(guide: Guide, policy: GuidePolicy): GuideMapResult

    /** Gives the drawing shape after the transform, in integer nm. */
    fun mapShape(shape: DrawingShape): DrawingShape

    /** True when the transform cannot keep [guide]. */
    fun breaksGuide(guide: Guide): Boolean = false
}

/** Gives the count of [guides] that the transform cannot keep. The question "Guides Will Be Deleted" needs it. */
fun WorkspaceTransform.countBrokenGuides(guides: List<Guide>): Int = guides.count(::breaksGuide)

/** Gives [image] with the matrix of the transform after the stored matrix. */
fun WorkspaceTransform.mapReferenceImage(image: ReferenceImage): ReferenceImage =
    image.copy(matrix = Matrix3.of(image.matrix).then(imageMatrix).toList())

/** Gives [drawing] with each shape through the shape map. */
fun WorkspaceTransform.mapDrawing(drawing: Drawing): Drawing = drawing.copy(shapes = drawing.shapes.map(::mapShape))

/**
 * The result of a transform of one workspace. The store writes all of it in one transaction.
 *
 * @property changed False for an identity transform: the store writes nothing.
 * @property deletedGuideIds The IDs of the guides that the transform deleted.
 * @property convertedPoints The new sub-datums of the converted guides. They are also in
 *   [workspace].
 */
data class TransformOutcome(
    val workspace: Workspace,
    val drawing: Drawing?,
    val changed: Boolean,
    val deletedGuideIds: List<String>,
    val convertedPoints: List<SubDatum>,
)

/** The name of a sub-datum that a converted guide gives. */
const val CONVERTED_GUIDE_NAME: String = "Guide"

/**
 * Applies the transform to all stored geometry of [workspace] and its [drawing].
 * First the active sub-datum becomes not active. Then the
 * sub-datums, the guides, the reference image matrix, and the shapes change.
 *
 * @param newPoint Makes the sub-datum of a converted guide.
 */
fun WorkspaceTransform.applyTo(
    workspace: Workspace,
    drawing: Drawing?,
    policy: GuidePolicy,
    newPoint: (guide: Guide, xUm: Double, yUm: Double) -> SubDatum = { _, x, y ->
        SubDatum(name = CONVERTED_GUIDE_NAME, x = x, y = y)
    },
): TransformOutcome {
    if (isIdentity) return TransformOutcome(workspace, drawing, false, emptyList(), emptyList())
    val guides = ArrayList<Guide>()
    val deleted = ArrayList<String>()
    val converted = ArrayList<SubDatum>()
    for (guide in workspace.guides) {
        when (val result = mapGuide(guide, policy)) {
            is GuideMapResult.Kept -> guides += result.guide
            GuideMapResult.Deleted -> deleted += guide.id
            is GuideMapResult.ConvertedToPoint -> {
                deleted += guide.id
                converted += newPoint(guide, result.xUm, result.yUm)
            }
        }
    }
    val newWorkspace = workspace.copy(
        activeSubDatumId = null,
        subDatums = workspace.subDatums.map(::mapPoint) + converted,
        guides = guides,
        referenceImage = workspace.referenceImage?.let(::mapReferenceImage),
    )
    return TransformOutcome(newWorkspace, drawing?.let(::mapDrawing), true, deleted, converted)
}

/** Gives +0.0 for −0.0. Other values do not change. */
internal fun positiveZero(value: Double): Double = if (value == 0.0) 0.0 else value
