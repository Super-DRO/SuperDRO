package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import org.superdro.core.ui.navigation.TabEntryScope

/**
 * The video backdrop of the graphical view: the video in fill
 * mode. The graphics area puts it under the Compose `Canvas` of the graphical view and makes the
 * background of the canvas transparent while [CameraVideoUiState.transparentBackground] is true.
 * The play button, the crosshair color, the view lock, and the overlay rules are in the video
 * presenter of the tab ([rememberCameraVideo]). The view shows only while the user wants the video
 * and "Camera setup" is closed.
 */
@Composable
fun TabEntryScope.CameraBackdrop(modifier: Modifier = Modifier) {
    val video = rememberCameraVideo() ?: return
    val state by video.state.collectAsState()
    if (!state.viewWanted) return
    CameraVideoView(
        fill = true,
        videoWidth = state.videoWidth,
        videoHeight = state.videoHeight,
        onTarget = video::onTarget,
        onTargetGone = video::onTargetGone,
        modifier = modifier.clipToBounds(),
    )
}
