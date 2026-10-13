package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.superdro.core.camera.VideoTarget

/** The test tag of the video view. */
const val CAMERA_VIDEO_VIEW_TAG: String = "camera-video-view"

/**
 * The view of the camera video. On Android it is the
 * `CameraSurfaceView` of `core:camera` in an `AndroidView`, so the Compose content above it (the
 * crosshairs, the graphical view) draws over the video. The JVM shows a placeholder.
 *
 * @param fill True fills the view and the parent cuts the parts outside (the backdrop and the
 *   camera screen). False fits the video in the view (the setup preview).
 * @param videoWidth The width of the mode: the buffer width of the surface.
 * @param videoHeight The height of the mode.
 * @param onTarget Gets the target when the surface exists, and again after each change of the buffer size.
 * @param onTargetGone Gets the target when its surface goes away.
 */
@Composable
expect fun CameraVideoView(
    fill: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    onTarget: (target: VideoTarget, width: Int, height: Int) -> Unit,
    onTargetGone: (target: VideoTarget) -> Unit,
    modifier: Modifier = Modifier,
)
