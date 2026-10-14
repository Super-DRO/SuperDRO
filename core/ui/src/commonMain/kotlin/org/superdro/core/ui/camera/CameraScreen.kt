package org.superdro.core.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.FrameRateText
import org.superdro.core.camera.VideoFit
import org.superdro.core.camera.VideoTarget
import org.superdro.core.resources.Res
import org.superdro.core.resources.camera_button_ok
import org.superdro.core.resources.camera_button_setup
import org.superdro.core.resources.camera_calibration_clear
import org.superdro.core.resources.camera_calibration_title
import org.superdro.core.resources.camera_color_black
import org.superdro.core.resources.camera_color_cherry
import org.superdro.core.resources.camera_color_dark_green
import org.superdro.core.resources.camera_color_dark_magenta
import org.superdro.core.resources.camera_color_default
import org.superdro.core.resources.camera_color_white
import org.superdro.core.resources.camera_crosshair_color
import org.superdro.core.resources.camera_dialog_title
import org.superdro.core.resources.camera_keep_overlays
import org.superdro.core.resources.camera_keep_overlays_summary
import org.superdro.core.resources.camera_open_settings
import org.superdro.core.resources.camera_play_needs_setup
import org.superdro.core.resources.camera_screen_no_camera
import org.superdro.core.resources.camera_screen_title
import org.superdro.core.resources.camera_setup_cancel
import org.superdro.core.resources.camera_ui_calibrating
import org.superdro.core.resources.camera_ui_crosshairs
import org.superdro.core.resources.camera_ui_dim
import org.superdro.core.resources.camera_ui_distance_in
import org.superdro.core.resources.camera_ui_distance_mm
import org.superdro.core.resources.camera_ui_no_camera_probe
import org.superdro.core.resources.camera_ui_play
import org.superdro.core.resources.camera_ui_point_title
import org.superdro.core.resources.camera_ui_scale_known
import org.superdro.core.resources.camera_ui_scale_move
import org.superdro.core.resources.camera_ui_show_crosshairs
import org.superdro.core.resources.camera_ui_status_detail
import org.superdro.core.resources.camera_ui_status_detail_no_fps
import org.superdro.core.resources.camera_ui_status_off
import org.superdro.core.resources.camera_ui_status_starting
import org.superdro.core.resources.camera_ui_stop
import org.superdro.core.resources.camera_ui_view_lock
import org.superdro.core.resources.ic_camera_crosshairs
import org.superdro.core.resources.ic_camera_lock_open
import org.superdro.core.resources.ic_camera_opacity
import org.superdro.core.resources.ic_camera_stop
import org.superdro.core.resources.ic_camera_straighten
import org.superdro.core.resources.ic_camera_videocam
import org.superdro.core.resources.ic_camera_visibility
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.ic_play_arrow
import org.superdro.core.resources.ic_settings
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.presenter.collectLiveState
import org.superdro.core.ui.readout.ReadoutTexts
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentSwitchColors

/** The test tag of the camera screen. */
const val CAMERA_SCREEN_TAG: String = "camera-screen"

/** The test tag of the play button. */
const val CAMERA_PLAY_TAG: String = "camera-play"

/** The test tag of the text of the status line of the video. */
const val CAMERA_STATUS_TAG: String = "camera-status"

/** The test tag of the video area. */
const val CAMERA_VIDEO_AREA_TAG: String = "camera-video-area"

/** The test tag of the text of the image scale. */
const val CAMERA_SCALE_TAG: String = "camera-scale"

/** The test tag of the dialog of the video. */
const val CAMERA_DIALOG_TAG: String = "camera-dialog"

/** The width of the control panel in landscape. */
private val PanelWidth = 380.dp

/** The color of the video area: the video shows on a dark field in each theme. */
private val VideoFieldColor = Color(0xFF101418)

/** The text color on the video field. */
private val VideoFieldText = Color(0xFFE0E3E8)

/** The events of the camera screen. */
interface CameraScreenEvents {
    fun onPlayTapped()
    fun onSetup()
    fun onViewLockToggled()
    fun onCrosshairsShown(shown: Boolean)
    fun onDimmed(dimmed: Boolean)
    fun onCrosshairColor(color: CrosshairColor)
    fun onKeepOverlays(keep: Boolean)
    fun onStartCalibration(method: CalibrationMethod)
    fun onCancelCalibration()
    fun onImageTouched(viewX: Double, viewY: Double, viewWidth: Double, viewHeight: Double)
    fun onDistanceText(text: String)
    fun onDistanceConfirmed()
    fun onClearScale()
    fun onProbeSelected(id: String)
    fun onDialogDismissed()
    fun onDialogSetup()
    fun onOpenSettings()
    fun onTarget(target: VideoTarget, width: Int, height: Int)
    fun onTargetGone(target: VideoTarget)
}

/**
 * The camera screen: the live video in fill mode with the crosshairs above it,
 * the play button, "Camera setup", the crosshair switches and colors, "keep overlays", the image
 * scale, and the point under the crosshairs. It is stateless.
 *
 * In landscape the controls are in a panel on the right. In portrait they are below the video.
 */
@Composable
fun CameraScreen(
    video: CameraVideoUiState,
    screen: CameraScreenUiState,
    point: StateFlow<CameraPointUiState>,
    events: CameraScreenEvents,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landscape = LocalWindowLayout.current.landscape
    ScreenScaffold(title = stringResource(Res.string.camera_screen_title), onBack = onBack, modifier = modifier.testTag(CAMERA_SCREEN_TAG)) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                VideoArea(video, screen, events, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Controls(video, screen, point, events, Modifier.width(PanelWidth).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                VideoArea(video, screen, events, Modifier.weight(0.55f).fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Controls(video, screen, point, events, Modifier.weight(0.45f).fillMaxWidth())
            }
        }
    }
    video.dialog?.let { dialog -> VideoDialog(dialog, events) }
    val session = screen.calibration
    if (session != null && session.step == CalibrationStep.Distance) DistanceDialog(screen, events)
}

@Composable
private fun VideoArea(video: CameraVideoUiState, screen: CameraScreenUiState, events: CameraScreenEvents, modifier: Modifier) {
    val cursor = MaterialTheme.colorScheme.secondary.toArgb().toLong() and 0xFFFFFFFFL
    BoxWithConstraints(modifier.background(VideoFieldColor).clipToBounds().testTag(CAMERA_VIDEO_AREA_TAG)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toDouble()
        val heightPx = with(density) { maxHeight.toPx() }.toDouble()
        if (video.viewWanted) {
            CameraVideoView(
                fill = true,
                videoWidth = video.videoWidth,
                videoHeight = video.videoHeight,
                onTarget = events::onTarget,
                onTargetGone = events::onTargetGone,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The text is below the center, so that it does not hide the crosshairs.
        if (!video.active) VideoPlaceholder(video, events, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
        if (video.crosshairsShown) {
            val scale = VideoFit.scale(widthPx, heightPx, video.videoWidth, video.videoHeight, fill = true)
            CrosshairOverlay(
                locked = video.viewLock.locked,
                color = Color(video.crosshairArgb(cursor)),
                screenPixelsPerMm = video.imagePixelsPerMm?.takeIf { video.active }?.let { perMm -> perMm * scale },
                metric = screen.metric,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val session = screen.calibration
        if (session != null) {
            CalibrationMarks(session, video, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().pointerInput(session) {
                    detectTapGestures { offset ->
                        events.onImageTouched(offset.x.toDouble(), offset.y.toDouble(), size.width.toDouble(), size.height.toDouble())
                    }
                },
            )
            CalibrationBanner(session, events, Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
    }
}

@Composable
private fun VideoPlaceholder(video: CameraVideoUiState, events: CameraScreenEvents, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            video.starting -> CircularProgressIndicator(color = VideoFieldText)
            else -> Icon(painterResource(Res.drawable.ic_camera_videocam), contentDescription = null, tint = VideoFieldText, modifier = Modifier.size(48.dp))
        }
        val text = when {
            !video.attached -> Res.string.camera_screen_no_camera
            !video.configured -> Res.string.camera_play_needs_setup
            video.starting -> Res.string.camera_ui_status_starting
            else -> Res.string.camera_ui_status_off
        }
        Text(stringResource(text), color = VideoFieldText, style = MaterialTheme.typography.bodyLarge)
        if (video.attached && !video.configured) {
            FilledTonalButton(onClick = events::onSetup, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.camera_button_setup))
            }
        }
    }
}

@Composable
private fun CalibrationMarks(session: CalibrationSession, video: CameraVideoUiState, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        listOfNotNull(session.firstImage, session.secondImage).forEach { point ->
            val (x, y) = CameraCalibrationFlow.viewPoint(point, size.width.toDouble(), size.height.toDouble(), video.videoWidth, video.videoHeight)
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x.toFloat(), y.toFloat()), style = Stroke(4.dp.toPx()))
            drawCircle(color, radius = 10.dp.toPx(), center = Offset(x.toFloat(), y.toFloat()), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun CalibrationBanner(session: CalibrationSession, events: CameraScreenEvents, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_camera_straighten), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(stringResource(Res.string.camera_ui_calibrating), style = MaterialTheme.typography.labelLarge)
                Text(CameraTexts.key(session.instructionKey).resolve(), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = events::onCancelCalibration, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button), colors = AccentButtons.text()) {
                Text(stringResource(Res.string.camera_setup_cancel))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    video: CameraVideoUiState,
    screen: CameraScreenUiState,
    point: StateFlow<CameraPointUiState>,
    events: CameraScreenEvents,
    modifier: Modifier,
) {
    val targets = LocalTouchTargets.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusLine(video)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(targets.gap), verticalArrangement = Arrangement.spacedBy(targets.gap)) {
            val playing = video.play.wanted
            FilledTonalButton(
                onClick = events::onPlayTapped,
                enabled = playing || (video.attached && video.configured),
                modifier = Modifier.heightIn(min = targets.button).testTag(CAMERA_PLAY_TAG),
            ) {
                Icon(painterResource(if (playing) Res.drawable.ic_camera_stop else Res.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playing) Res.string.camera_ui_stop else Res.string.camera_ui_play))
            }
            OutlinedButton(onClick = events::onSetup, colors = AccentButtons.outlined(), modifier = Modifier.heightIn(min = targets.button)) {
                Icon(painterResource(Res.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.camera_button_setup))
            }
        }
        SectionTitle(Res.string.camera_ui_crosshairs)
        SwitchRow(Res.string.camera_ui_show_crosshairs, Res.drawable.ic_camera_visibility, video.crosshairsShown, events::onCrosshairsShown)
        SwitchRow(Res.string.camera_ui_dim, Res.drawable.ic_camera_opacity, video.dimmed, events::onDimmed)
        SwitchRow(
            Res.string.camera_ui_view_lock,
            if (video.viewLock.locked) Res.drawable.ic_lock else Res.drawable.ic_camera_lock_open,
            video.viewLock.locked,
            onChange = { events.onViewLockToggled() },
        )
        ColorChoice(video.settings.crosshairColor, events)
        SwitchRow(Res.string.camera_keep_overlays, Res.drawable.ic_camera_crosshairs, video.settings.keepOverlays, events::onKeepOverlays, Res.string.camera_keep_overlays_summary)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ScaleSection(video, screen, events)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PointSection(screen, point, events)
    }
}

@Composable
private fun StatusLine(video: CameraVideoUiState) {
    val colors = MaterialTheme.colorScheme
    val (container, content, icon) = when {
        video.active -> Triple(colors.primaryContainer, colors.onPrimaryContainer, Res.drawable.ic_camera_videocam)
        video.starting -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, Res.drawable.ic_hourglass)
        else -> Triple(colors.surfaceContainerHighest, colors.onSurface, Res.drawable.ic_camera_stop)
    }
    val text = when {
        video.active -> {
            val fps = video.fps?.let { rate -> FrameRateText.format(rate, FrameRateText.MEASURED_DECIMALS) }
            if (fps != null) {
                stringResource(Res.string.camera_ui_status_detail, video.videoWidth, video.videoHeight, fps)
            } else {
                stringResource(Res.string.camera_ui_status_detail_no_fps, video.videoWidth, video.videoHeight)
            }
        }
        video.starting -> stringResource(Res.string.camera_ui_status_starting)
        else -> stringResource(Res.string.camera_ui_status_off)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(CAMERA_STATUS_TAG))
        }
    }
}

@Composable
private fun SectionTitle(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SwitchRow(
    label: StringResource,
    icon: DrawableResource,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: StringResource? = null,
) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = summary?.let { value -> { Text(stringResource(value)) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, colors = accentSwitchColors()) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { toggleableState = ToggleableState(checked) },
    )
}

private fun CrosshairColor.label(): StringResource = when (this) {
    CrosshairColor.Default -> Res.string.camera_color_default
    CrosshairColor.White -> Res.string.camera_color_white
    CrosshairColor.Black -> Res.string.camera_color_black
    CrosshairColor.Cherry -> Res.string.camera_color_cherry
    CrosshairColor.DarkGreen -> Res.string.camera_color_dark_green
    CrosshairColor.DarkMagenta -> Res.string.camera_color_dark_magenta
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoice(selected: CrosshairColor, events: CameraScreenEvents) {
    val cursor = MaterialTheme.colorScheme.secondary.toArgb().toLong() and 0xFFFFFFFFL
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.camera_crosshair_color), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CrosshairColor.entries.forEach { color ->
                FilterChip(
                    selected = color == selected,
                    onClick = { events.onCrosshairColor(color) },
                    label = { Text(stringResource(color.label())) },
                    leadingIcon = {
                        Box(
                            Modifier.size(16.dp).background(Color(color.resolve(cursor)), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun ScaleSection(video: CameraVideoUiState, screen: CameraScreenUiState, events: CameraScreenEvents) {
    val targets = LocalTouchTargets.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(Res.string.camera_calibration_title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_camera_straighten), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(CameraScreenPresenter.scaleState(video).resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CAMERA_SCALE_TAG))
        }
        CameraScreenPresenter.rotationText(video)?.let { text -> Text(text.resolve(), style = MaterialTheme.typography.bodyMedium) }
        screen.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(message.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(targets.gap)) {
            OutlinedButton(
                onClick = { events.onStartCalibration(CalibrationMethod.KnownDistance) },
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = targets.button),
            ) { Text(stringResource(Res.string.camera_ui_scale_known)) }
            OutlinedButton(
                onClick = { events.onStartCalibration(CalibrationMethod.MachineMove) },
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = targets.button),
            ) { Text(stringResource(Res.string.camera_ui_scale_move)) }
        }
        if (video.calibration != null) {
            TextButton(onClick = events::onClearScale, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
                Text(stringResource(Res.string.camera_calibration_clear))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointSection(screen: CameraScreenUiState, point: StateFlow<CameraPointUiState>, events: CameraScreenEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(Res.string.camera_ui_point_title)
        if (screen.probes.isEmpty()) {
            Text(stringResource(Res.string.camera_ui_no_camera_probe), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (screen.probes.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                screen.probes.forEach { probe ->
                    FilterChip(
                        selected = probe.id == screen.probe?.id,
                        onClick = { events.onProbeSelected(probe.id) },
                        label = { Text(probe.name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        } else {
            screen.probe?.let { probe -> Text(probe.name, style = MaterialTheme.typography.bodyLarge) }
        }
        // Only this section reads the point, so a new frame recomposes no other part of the screen.
        val current by point.collectLiveState()
        val unit = current.unit?.let { value -> stringResource(ReadoutTexts.unitSymbol(value)) }.orEmpty()
        AxisValue("X", current.x, unit)
        AxisValue("Y", current.y, unit)
    }
}

@Composable
private fun AxisValue(axis: String, value: String?, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(axis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
        Text(if (value == null) "—" else "$value $unit", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun VideoDialog(dialog: CameraVideoDialog, events: CameraScreenEvents) {
    AlertDialog(
        onDismissRequest = events::onDialogDismissed,
        modifier = Modifier.testTag(CAMERA_DIALOG_TAG),
        title = { Text(stringResource(Res.string.camera_dialog_title)) },
        text = { Text(dialog.message.resolve()) },
        confirmButton = {
            when {
                dialog.openSettings -> TextButton(onClick = events::onOpenSettings, colors = AccentButtons.text()) {
                    Text(stringResource(Res.string.camera_open_settings))
                }
                dialog.offersSetup -> TextButton(onClick = events::onDialogSetup, colors = AccentButtons.text()) {
                    Text(stringResource(Res.string.camera_button_setup))
                }
                else -> TextButton(onClick = events::onDialogDismissed, colors = AccentButtons.text()) {
                    Text(stringResource(Res.string.camera_button_ok))
                }
            }
        },
        dismissButton = if (dialog.offersSetup || dialog.openSettings) {
            { TextButton(onClick = events::onDialogDismissed, colors = AccentButtons.text()) { Text(stringResource(Res.string.camera_setup_cancel)) } }
        } else {
            null
        },
    )
}

@Composable
private fun DistanceDialog(screen: CameraScreenUiState, events: CameraScreenEvents) {
    AlertDialog(
        onDismissRequest = events::onCancelCalibration,
        title = { Text(stringResource(Res.string.camera_calibration_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                screen.message?.let { message -> Text(message.resolve(), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = screen.distanceText,
                    onValueChange = events::onDistanceText,
                    label = { Text(stringResource(if (screen.metric) Res.string.camera_ui_distance_mm else Res.string.camera_ui_distance_in)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = accentTextFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = events::onDistanceConfirmed, colors = AccentButtons.text()) { Text(stringResource(Res.string.camera_button_ok)) }
        },
        dismissButton = {
            TextButton(onClick = events::onCancelCalibration, colors = AccentButtons.text()) { Text(stringResource(Res.string.camera_setup_cancel)) }
        },
    )
}
