package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.superdro.core.camera.CameraSurfaceView
import org.superdro.core.camera.SurfaceVideoTarget
import org.superdro.core.camera.VideoTarget

/** The surface target and the buffer size of one video view. */
private class SurfaceSlot {
    var target: SurfaceVideoTarget? = null
    var width: Int = -1
    var height: Int = -1
}

/**
 * The Android video view: a `SurfaceView` in an `AndroidView`. The
 * `SurfaceView` stays under the window, so the Compose `Canvas` above it draws over the video.
 * The view sets the buffer size of the mode before it gives the target.
 */
@Composable
actual fun CameraVideoView(
    fill: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    onTarget: (target: VideoTarget, width: Int, height: Int) -> Unit,
    onTargetGone: (target: VideoTarget) -> Unit,
    modifier: Modifier,
) {
    val giveTarget by rememberUpdatedState(onTarget)
    val takeTarget by rememberUpdatedState(onTargetGone)
    val slot = remember { SurfaceSlot() }
    AndroidView(
        factory = { context ->
            CameraSurfaceView(context, fill) { target ->
                val old = slot.target
                slot.target = target
                if (target != null) {
                    giveTarget(target, slot.width.coerceAtLeast(0), slot.height.coerceAtLeast(0))
                } else if (old != null) {
                    takeTarget(old)
                }
            }
        },
        modifier = modifier.testTag(CAMERA_VIDEO_VIEW_TAG),
        update = { view ->
            if (view.fill != fill) {
                view.fill = fill
                view.requestLayout()
            }
            if (videoWidth != slot.width || videoHeight != slot.height) {
                view.setVideoSize(videoWidth, videoHeight)
                slot.width = videoWidth
                slot.height = videoHeight
                slot.target?.let { target -> giveTarget(target, videoWidth, videoHeight) }
            }
        },
        onRelease = {
            slot.target?.let { target -> takeTarget(target) }
            slot.target = null
        },
    )
}
