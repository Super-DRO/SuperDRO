package org.superdro.core.model

import kotlinx.serialization.Serializable

/**
 * The state of the display pane of the graphical view for one machine profile. Each machine
 * profile has its own values. The reference image has its alpha in
 * [ReferenceImage.alpha] of the workspace.
 *
 * @property toolShown The tool layer (the cutter or the lathe tool outline) shows.
 * @property toolDimmed The tool layer uses the alpha 127 in place of 191.
 * @property guidesShown The guides layer shows. Snap to guide, the automatic tool offset, and the
 *   automatic proximity warning act only when it shows.
 * @property crosshairsShown The position cursor shows.
 * @property crosshairsDimmed The position cursor uses half of its alpha.
 * @property viewLocked The view is locked to the crosshairs: follow mode is on and a drag does not
 *   pan the view.
 * @property subDatumsShown The points layer shows.
 * @property ghostSubDatumsShown The ghost points show while an armed function restricts the view.
 * @property ghostGuidesShown The ghost guides show while an armed function restricts the view.
 * @property drawingShown The drawing layer shows.
 * @property drawingDimmed The drawing layer uses the alpha 127 in place of 255.
 * @property cameraShown The video of the USB camera shows behind the view.
 */
@Serializable
data class GraphicalDisplay(
    val toolShown: Boolean = true,
    val toolDimmed: Boolean = true,
    val guidesShown: Boolean = true,
    val crosshairsShown: Boolean = true,
    val crosshairsDimmed: Boolean = false,
    val viewLocked: Boolean = false,
    val subDatumsShown: Boolean = true,
    val ghostSubDatumsShown: Boolean = true,
    val ghostGuidesShown: Boolean = true,
    val drawingShown: Boolean = true,
    val drawingDimmed: Boolean = false,
    val cameraShown: Boolean = false,
)

/**
 * The pass record of the thread cut panel for one workspace. The lengths are X diameters in µm.
 *
 * @property deepestUm `w_state_cut_deepest`: the deepest recorded X diameter, or null.
 * @property previousDeepestUm `w_state_cut_previous_deepest`: the record before it, or null.
 * @property threadId `w_state_cut_deepest_thread`: the thread of the two records, or null.
 */
@Serializable
data class ThreadPass(
    val deepestUm: Double? = null,
    val previousDeepestUm: Double? = null,
    val threadId: String? = null,
)
