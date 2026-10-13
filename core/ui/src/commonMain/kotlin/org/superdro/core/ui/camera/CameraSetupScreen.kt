package org.superdro.core.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.camera.ModeTestState
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.resources.Res
import org.superdro.core.resources.camera_open_settings
import org.superdro.core.resources.camera_setup_camera
import org.superdro.core.resources.camera_setup_cancel
import org.superdro.core.resources.camera_setup_find
import org.superdro.core.resources.camera_setup_last_test
import org.superdro.core.resources.camera_setup_mode
import org.superdro.core.resources.camera_setup_no_modes
import org.superdro.core.resources.camera_setup_preview_idle
import org.superdro.core.resources.camera_setup_preview_starting
import org.superdro.core.resources.camera_setup_save
import org.superdro.core.resources.camera_setup_stop_test
import org.superdro.core.resources.camera_setup_test
import org.superdro.core.resources.camera_setup_title
import org.superdro.core.resources.camera_ui_loading
import org.superdro.core.resources.camera_ui_mode_bad
import org.superdro.core.resources.camera_ui_mode_good
import org.superdro.core.resources.camera_ui_progress
import org.superdro.core.resources.ic_camera_stop
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_play_arrow
import org.superdro.core.resources.ic_sync
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentColor

/** The test tag of the camera setup screen. */
const val CAMERA_SETUP_TAG: String = "camera-setup"

/** The test tag of the "Test" button. */
const val CAMERA_SETUP_TEST_TAG: String = "camera-setup-test"

/** The test tag of the "Save" button. */
const val CAMERA_SETUP_SAVE_TAG: String = "camera-setup-save"

/** The test tag of the result text. */
const val CAMERA_SETUP_RESULT_TAG: String = "camera-setup-result"

/** The test tag of the error text at the top. */
const val CAMERA_SETUP_ERROR_TAG: String = "camera-setup-error"

/** The height of the preview. */
private val PreviewHeight = 220.dp

/**
 * The screen "Camera setup": the error
 * text, the preview in fit mode, the mode test with the result and the progress, "Last test", the
 * camera list with "Find cameras", the mode list, and "Cancel" and "Save". It is stateless.
 *
 * In landscape the preview and the test are on the left and the lists are on the right.
 */
@Composable
fun CameraSetupScreen(state: CameraSetupUiState, events: CameraSetupEvents, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val landscape = LocalWindowLayout.current.landscape
    ScreenScaffold(title = stringResource(Res.string.camera_setup_title), onBack = onBack, modifier = modifier.testTag(CAMERA_SETUP_TAG)) {
        Column(Modifier.fillMaxSize()) {
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ErrorPanel(state, events)
                        Preview(state, events)
                        TestPanel(state, events)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CameraList(state, events)
                        ModeList(state, events)
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ErrorPanel(state, events)
                    Preview(state, events)
                    TestPanel(state, events)
                    CameraList(state, events)
                    ModeList(state, events)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                    Text(stringResource(Res.string.camera_setup_cancel))
                }
                FilledTonalButton(
                    onClick = events::onSave,
                    enabled = state.canSave,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(CAMERA_SETUP_SAVE_TAG),
                ) {
                    Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.camera_setup_save))
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(state: CameraSetupUiState, events: CameraSetupEvents) {
    val error = state.error ?: return
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_error), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(error.resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CAMERA_SETUP_ERROR_TAG))
            }
            if (state.openSettings) {
                Button(
                    onClick = events::onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
                ) {
                    Text(stringResource(Res.string.camera_open_settings))
                }
            }
        }
    }
}

@Composable
private fun Preview(state: CameraSetupUiState, events: CameraSetupEvents) {
    val colors = MaterialTheme.colorScheme
    val mode = state.test.mode ?: state.mode
    Box(
        Modifier.fillMaxWidth().height(PreviewHeight).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        if (state.test.previewOn && mode != null) {
            CameraVideoView(
                fill = false,
                videoWidth = mode.width,
                videoHeight = mode.height,
                onTarget = events::onPreviewTarget,
                onTargetGone = events::onPreviewTargetGone,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val text = if (state.test.state.isRunning) Res.string.camera_setup_preview_starting else Res.string.camera_setup_preview_idle
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun TestPanel(state: CameraSetupUiState, events: CameraSetupEvents) {
    val test = state.test
    val running = test.state.isRunning
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(resultIcon(test.state)), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(state.result.resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).testTag(CAMERA_SETUP_RESULT_TAG))
        }
        if (running || test.state == ModeTestState.Ok || test.state == ModeTestState.Failed) {
            val label = stringResource(Res.string.camera_ui_progress)
            LinearProgressIndicator(
                progress = { test.progress / 100f },
                color = accentColor(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = events::onTestTapped,
                enabled = running || state.canTest,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(CAMERA_SETUP_TEST_TAG),
            ) {
                Icon(painterResource(if (running) Res.drawable.ic_camera_stop else Res.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (running) Res.string.camera_setup_stop_test else Res.string.camera_setup_test))
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text(stringResource(Res.string.camera_setup_last_test), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.lastTest.resolve(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun resultIcon(state: ModeTestState) = when (state) {
    ModeTestState.KnownGood, ModeTestState.Ok -> Res.drawable.ic_check
    ModeTestState.KnownBad, ModeTestState.Failed -> Res.drawable.ic_close
    ModeTestState.Testing, ModeTestState.Verifying -> Res.drawable.ic_sync
    ModeTestState.Untested -> Res.drawable.ic_play_arrow
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun CameraList(state: CameraSetupUiState, events: CameraSetupEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(Res.string.camera_setup_camera))
        state.cameras.forEach { camera ->
            val selected = camera.deviceName == state.camera?.deviceName
            SelectableRow(
                selected = selected,
                text = CameraTexts.cameraLabel(camera).resolve(),
                onClick = { events.onCameraSelected(camera) },
            )
        }
        OutlinedButton(
            onClick = events::onFindCameras,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
        ) {
            Icon(painterResource(Res.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.camera_setup_find))
        }
    }
}

@Composable
private fun ModeList(state: CameraSetupUiState, events: CameraSetupEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(Res.string.camera_setup_mode))
        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = accentColor())
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.camera_ui_loading), style = MaterialTheme.typography.bodyLarge)
            }
            state.noModes -> Text(stringResource(Res.string.camera_setup_no_modes), style = MaterialTheme.typography.bodyLarge)
            else -> state.modes.forEach { row ->
                val status = row.label.status
                SelectableRow(
                    selected = row.mode == state.mode,
                    text = CameraTexts.modeLabel(row.label).resolve(),
                    bold = status == CameraModeStatus.Good,
                    struck = status == CameraModeStatus.Bad,
                    status = status,
                    onClick = { events.onModeSelected(row.mode) },
                )
            }
        }
    }
}

/**
 * One line of a list with a radio button. A GOOD mode shows in bold and a BAD mode with a line
 * through it. The status also has an icon and a text.
 */
@Composable
private fun SelectableRow(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    bold: Boolean = false,
    struck: Boolean = false,
    status: CameraModeStatus? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = null, colors = accentRadioColors()) },
        supportingContent = status?.let { value ->
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (value == CameraModeStatus.Good) Res.drawable.ic_check else Res.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (value == CameraModeStatus.Good) Res.string.camera_ui_mode_good else Res.string.camera_ui_mode_bad))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.clip(MaterialTheme.shapes.small).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
