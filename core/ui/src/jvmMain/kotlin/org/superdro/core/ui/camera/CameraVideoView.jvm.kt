package org.superdro.core.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.superdro.core.camera.VideoFit
import org.superdro.core.camera.VideoTarget

/** The video target of the JVM: no surface. The fake back end of the tests and the previews accepts it. */
class PlaceholderVideoTarget : VideoTarget

/**
 * The JVM video view: a placeholder with the size of the video (fit or fill), for the tests and
 * the screenshots. It gives a [PlaceholderVideoTarget] at once.
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
    val target = remember { PlaceholderVideoTarget() }
    val giveTarget by rememberUpdatedState(onTarget)
    val takeTarget by rememberUpdatedState(onTargetGone)
    LaunchedEffect(target, videoWidth, videoHeight) { giveTarget(target, videoWidth, videoHeight) }
    DisposableEffect(target) { onDispose { takeTarget(target) } }
    BoxWithConstraints(modifier.testTag(CAMERA_VIDEO_VIEW_TAG).clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val (width, height) = VideoFit.size(
            with(density) { maxWidth.roundToPx() },
            with(density) { maxHeight.roundToPx() },
            VideoFit.aspect(videoWidth, videoHeight),
            fill,
        )
        Box(
            Modifier
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .background(Brush.linearGradient(listOf(Color(0xFF37474F), Color(0xFF102027)))),
        )
    }
}
