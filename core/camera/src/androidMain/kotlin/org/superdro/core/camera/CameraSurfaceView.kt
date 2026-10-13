package org.superdro.core.camera

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The video view of the camera. The Compose UI puts it into an
 * `AndroidView` under the `Canvas` of the crosshairs. [fill] false fits the video in the view
 * (the setup preview). [fill] true fills the view, and the parent cuts the parts outside (the
 * backdrop and the camera screen).
 *
 * [onSurface] gets the [SurfaceVideoTarget] when the surface exists, and null when it goes away.
 */
class CameraSurfaceView(
    context: Context,
    var fill: Boolean = true,
    private val onSurface: (SurfaceVideoTarget?) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var videoWidth = 0
    private var videoHeight = 0

    init {
        holder.addCallback(this)
    }

    /** Sets the mode size: the buffer size of the surface and the aspect ratio of the view. */
    fun setVideoSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        if (width > 0 && height > 0) holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val (width, height) = VideoFit.size(maxWidth, maxHeight, VideoFit.aspect(videoWidth, videoHeight), fill)
        setMeasuredDimension(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        onSurface(SurfaceVideoTarget(holder.surface))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurface(null)
    }
}
