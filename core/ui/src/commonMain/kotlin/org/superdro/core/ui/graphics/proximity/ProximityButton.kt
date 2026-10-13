package org.superdro.core.ui.graphics.proximity

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_proximity_off
import org.superdro.core.resources.graphics_proximity_on
import org.superdro.core.resources.graphics_proximity_warning
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle

/** The test tag of the proximity warning button of the axis details. */
const val PROXIMITY_BUTTON_TAG: String = "axis-details-proximity"

/** The rules of the proximity warning target. */
object ProximityTargets {

    /** True when the warning of [axis] is on in [workspace]. */
    fun isOn(workspace: Workspace?, axis: AxisId): Boolean = workspace?.proximity?.axis == axis

    /**
     * Gives [workspace] with the warning of [axis] on (the target 0 on the shown value), or off
     * when the warning of [axis] is on. One axis at a time has the warning.
     */
    fun toggle(workspace: Workspace, axis: AxisId): Workspace =
        if (isOn(workspace, axis)) {
            workspace.copy(proximity = null)
        } else {
            workspace.copy(proximity = ProximityTarget(axis = axis, targetUm = 0.0, absolute = false))
        }
}

/**
 * The proximity warning button of the linear axis details. The state shows as a text and
 * as the button color.
 */
@Composable
fun ProximityButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.graphics_proximity_warning)
    val state = stringResource(if (on) Res.string.graphics_proximity_on else Res.string.graphics_proximity_off)
    DroButton(
        onClick = onClick,
        contentDescription = label,
        stateDescription = state,
        style = if (on) DroButtonStyle.Active else DroButtonStyle.Primary,
        modifier = modifier.testTag(PROXIMITY_BUTTON_TAG),
    ) {
        Text("$label: $state", style = MaterialTheme.typography.titleMedium)
    }
}
