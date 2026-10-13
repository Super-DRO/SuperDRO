package org.superdro.core.ui.camera

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraException
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.ModeLabel
import org.superdro.core.camera.ModeTestResult
import org.superdro.core.ui.text.UiText

/**
 * The texts of the camera screens. The camera messages of `core:camera`
 * carry the name of their string in `strings_camera.xml` ([UiText.Key]).
 */
object CameraTexts {

    /** The time after a test in which "Last test" shows "Now", in ms. */
    const val NOW_SPAN_MS: Long = 60_000L

    /** Gives the error of a failed [Result] of the camera back end. */
    fun errorOf(failure: Throwable): CameraError =
        (failure as? CameraException)?.error ?: CameraError(CameraErrorKind.CameraServiceUnavailable, failure.message)

    /**
     * True when only the app settings can give `CAMERA` (`CameraAccessResult.Refused` with
     * `openAppSettings`): the back end sets [CameraError.openAppSettings]. The detail text has no
     * part in the rule.
     */
    fun isBlocked(error: CameraError): Boolean = error.openAppSettings

    /** The message of [error]: the message of its kind, or `camera_permission_blocked` for a blocked permission. */
    fun message(error: CameraError): UiText =
        if (isBlocked(error)) key(BLOCKED_KEY) else key(error.kind.messageKey)

    /** The line of [camera] in the camera list: `label (key)`, or only the key when the label is the key. */
    fun cameraLabel(camera: CameraIdentity): UiText =
        if (camera.labelIsKey) UiText.Plain(camera.key) else UiText.Key(CAMERA_FORMAT_KEY, "%1\$s (%2\$s)", listOf(camera.label, camera.key))

    /** The line of a mode of the mode list (the rate of the real interval). */
    fun modeLabel(label: ModeLabel): UiText {
        val codec = UiText.Key(ModeLabel.codecKey(label.codec), ModeLabel.codecName(label.codec))
        val args = buildList<Any> {
            add(codec)
            add(label.width)
            add(label.height)
            label.nominalFps?.let(::add)
            label.measuredFps?.let(::add)
        }
        return UiText.Key(label.formatKey, englishFormat(label.formatKey), args)
    }

    /** The result text of a mode test. */
    fun testResult(result: ModeTestResult): UiText = when (result) {
        is ModeTestResult.WorkingStarted ->
            UiText.Key(result.messageKey, result.englishText(), listOf(result.width, result.height, result.fps, result.startSecondsText()))
        is ModeTestResult.Working -> UiText.Key(result.messageKey, result.englishText(), listOf(result.width, result.height, result.fps))
        is ModeTestResult.WorkingNoFps -> UiText.Key(result.messageKey, result.englishText(), listOf(result.width, result.height))
        is ModeTestResult.Failed -> message(result.error)
        ModeTestResult.Bad, ModeTestResult.Stopped, ModeTestResult.Untested -> UiText.Key(result.messageKey, result.englishText())
    }

    /**
     * The value of "Last test": "Never" with no test, "Now" for a
     * test of the last minute, else the local time in the form `yyyy-MM-dd HH:mm`.
     */
    fun lastTest(updatedOn: Instant?, now: Instant, timeZone: TimeZone): UiText {
        if (updatedOn == null) return key(NEVER_KEY)
        val ageMs = (now - updatedOn).inWholeMilliseconds
        if (ageMs in 0 until NOW_SPAN_MS) return key(NOW_KEY)
        return UiText.Plain(dateText(updatedOn, timeZone))
    }

    /** The local time of [instant] in the form `yyyy-MM-dd HH:mm`. */
    fun dateText(instant: Instant, timeZone: TimeZone): String {
        val local = instant.toLocalDateTime(timeZone)
        fun two(value: Int) = value.toString().padStart(2, '0')
        return "${local.year.toString().padStart(4, '0')}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}"
    }

    /** A text of `strings_camera.xml` or `strings_camera_ui.xml` with no argument. */
    fun key(name: String): UiText = UiText.Key(name, name)

    private fun englishFormat(formatKey: String): String = when (formatKey) {
        "camera_mode_format_no_rate" -> "%1\$s %2\$d × %3\$d"
        "camera_mode_format_measured" -> "%1\$s %2\$d × %3\$d, %4\$s fps (measured %5\$s fps)"
        else -> "%1\$s %2\$d × %3\$d, %4\$s fps"
    }

    private const val CAMERA_FORMAT_KEY = "camera_camera_format"
    private const val BLOCKED_KEY = "camera_permission_blocked"
    private const val NEVER_KEY = "camera_setup_never"
    private const val NOW_KEY = "camera_setup_now"
}
