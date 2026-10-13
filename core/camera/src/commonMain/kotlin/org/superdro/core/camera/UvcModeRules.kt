package org.superdro.core.camera

import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus

/**
 * The rules of the mode list and of the default mode.
 */
object UvcModeRules {

    /** The pixel limit of step 5 of [bestMode]: 1920 × 1080. */
    const val FULL_HD_PIXELS: Long = 2_073_600L

    /** Keeps the usable modes ([UvcMode.isUsable]) in their order. */
    fun usable(modes: List<UvcMode>): List<UvcMode> = modes.filter { mode -> mode.isUsable }

    /**
     * Gives the lines of the mode list of "Camera setup": the usable modes, one for each codec and
     * frame size. Each line has the highest frame rate of its group, from the real intervals.
     * The order: more pixels first, then MJPEG, H.264, YUY2.
     */
    fun setupList(modes: List<UvcMode>): List<UvcMode> =
        usable(modes)
            .groupBy { mode -> Triple(mode.codec, mode.width, mode.height) }
            .values
            .map { group -> group.maxWith(compareBy { mode -> mode.frameRate }) }
            .sortedWith(
                compareByDescending<UvcMode> { mode -> mode.pixels }
                    .thenBy { mode -> mode.codec.ordinal }
                    .thenByDescending { mode -> mode.frameRate }
            )

    /**
     * Gives the best usable mode of [modes], else null:
     *
     * 1. MJPEG 1920 × 1080,
     * 2. H.264 1920 × 1080,
     * 3. MJPEG 1280 × 720,
     * 4. YUY2 640 × 480,
     * 5. else the largest mode with not more than 2 073 600 pixels, with the frame rate as the
     *    second key,
     * 6. else the smallest mode, with the higher frame rate first.
     *
     * In each step, a frame size with more than one interval gives the highest frame rate.
     */
    fun bestMode(modes: List<UvcMode>): UvcMode? {
        val candidates = usable(modes)
        if (candidates.isEmpty()) return null
        PREFERRED.forEach { (codec, width, height) ->
            candidates
                .filter { mode -> mode.codec == codec && mode.width == width && mode.height == height }
                .maxByOrNull { mode -> mode.frameRate }
                ?.let { mode -> return mode }
        }
        candidates
            .filter { mode -> mode.pixels <= FULL_HD_PIXELS }
            .maxWithOrNull(compareBy<UvcMode> { mode -> mode.pixels }.thenBy { mode -> mode.frameRate })
            ?.let { mode -> return mode }
        return candidates.minWithOrNull(
            compareBy<UvcMode> { mode -> mode.pixels }.thenByDescending { mode -> mode.frameRate }
        )
    }

    /**
     * Gives the mode that "Camera setup" selects first:
     *
     * 1. the mode with the key [savedModeKey] (the saved selection of the profile and camera),
     * 2. else the best mode with the status GOOD, from the rule of [bestMode],
     * 3. else the best usable mode.
     *
     * @param modes The modes of the list, normally [setupList].
     * @param results The mode memory of this camera (`CameraModeResult` of its VID:PID).
     */
    fun setupDefault(modes: List<UvcMode>, savedModeKey: String?, results: List<CameraModeResult>): UvcMode? =
        savedOrGood(modes, savedModeKey, results) ?: bestMode(modes)

    /**
     * Gives the mode of the video start in the graphical view or the camera screen: only steps 1
     * and 2 of [setupDefault]. A camera with no saved selection and no GOOD mode does not start.
     */
    fun playMode(modes: List<UvcMode>, savedModeKey: String?, results: List<CameraModeResult>): UvcMode? =
        savedOrGood(modes, savedModeKey, results)

    /**
     * True when the camera is "configured": it is attached, and the
     * profile has a saved selection for its VID:PID, or one of its modes has the status GOOD.
     */
    fun isConfigured(attached: Boolean, hasSavedSelection: Boolean, results: List<CameraModeResult>): Boolean =
        attached && (hasSavedSelection || results.any { result -> result.status == CameraModeStatus.Good })

    private fun savedOrGood(modes: List<UvcMode>, savedModeKey: String?, results: List<CameraModeResult>): UvcMode? {
        val usableModes = usable(modes)
        if (savedModeKey != null) {
            usableModes.firstOrNull { mode -> mode.key == savedModeKey }?.let { mode -> return mode }
        }
        val goodKeys = results.filter { result -> result.status == CameraModeStatus.Good }.map { result -> result.modeKey }.toSet()
        return bestMode(usableModes.filter { mode -> mode.key in goodKeys })
    }

    private val PREFERRED: List<Triple<UvcCodec, Int, Int>> = listOf(
        Triple(UvcCodec.Mjpeg, 1920, 1080),
        Triple(UvcCodec.H264, 1920, 1080),
        Triple(UvcCodec.Mjpeg, 1280, 720),
        Triple(UvcCodec.Uncompressed, 640, 480),
    )
}
