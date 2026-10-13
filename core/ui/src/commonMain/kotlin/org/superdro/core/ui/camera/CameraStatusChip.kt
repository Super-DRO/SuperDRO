package org.superdro.core.ui.camera

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.camera_screen_title
import org.superdro.core.resources.camera_ui_chip_description
import org.superdro.core.resources.camera_ui_status_on
import org.superdro.core.resources.ic_camera_videocam
import org.superdro.core.ui.navigation.CameraRoute
import org.superdro.core.ui.navigation.TabEntryScope

/** The minimum height of the chip: the touch target of Material 3. */
private val MIN_TOUCH = 48.dp

/** The test tag of the camera chip of the status strip. */
const val CAMERA_CHIP_TAG: String = "camera-chip"

/**
 * The camera chip of the status strip: it shows while a UVC camera is attached, with an
 * icon and a text. A tap opens the camera screen of the tab. A graph with no camera UI
 * shows no chip.
 */
@Composable
fun CameraStatusItem(scope: TabEntryScope) {
    val ui = rememberCameraUi() ?: return
    val cameras by ui.backend.cameras.collectAsState()
    if (cameras.isEmpty()) return
    val video = scope.rememberCameraVideo() ?: return
    val state by video.state.collectAsState()
    CameraStatusChip(active = state.active, onClick = { scope.navigator.push(CameraRoute) })
}

/** The chip: "Camera", or "Video on" while the video of the tab plays. The touch target has 48 dp or more. */
@Composable
fun CameraStatusChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(Res.string.camera_ui_chip_description)
    Surface(
        onClick = onClick,
        color = if (active) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.testTag(CAMERA_CHIP_TAG).semantics { contentDescription = description }.heightIn(min = MIN_TOUCH),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_camera_videocam), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (active) Res.string.camera_ui_status_on else Res.string.camera_screen_title),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
