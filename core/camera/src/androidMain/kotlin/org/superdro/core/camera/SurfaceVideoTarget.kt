package org.superdro.core.camera

import android.view.Surface

/**
 * The Android [VideoTarget]: the `Surface` of a `SurfaceView`. The camera process draws the frames
 * into it. The frames do not cross the process
 * boundary. The holder of the view must have `setFixedSize(width, height)` of the mode before the
 * start ([CameraSurfaceView] does this).
 */
class SurfaceVideoTarget(val surface: Surface) : VideoTarget
