package org.superdro.core.ui.functions.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_file_download
import org.superdro.core.resources.ic_file_upload
import org.superdro.core.resources.import_profile_export
import org.superdro.core.resources.import_profile_export_failed
import org.superdro.core.resources.import_profile_export_text
import org.superdro.core.resources.import_profile_export_title
import org.superdro.core.resources.import_profile_exported
import org.superdro.core.resources.import_profile_import
import org.superdro.core.resources.import_profile_import_text
import org.superdro.core.resources.import_profile_import_title
import org.superdro.core.resources.import_profile_title
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the profile file screen. */
const val PROFILE_EXPORT_TAG: String = "import-profile-export"
const val PROFILE_IMPORT_TAG: String = "import-profile-import"

/** The UI state of the profile file screen. */
@Immutable
data class ProfileFileUiState(val loaded: Boolean = false, val profileName: String = "")

/**
 * The presenter of the profile file screen: the export of the SuperDRO profile file of the
 * profile of the tab. The file holds all profile settings and no device identity.
 *
 * @param messages Shows a short message in the snackbar of the frame.
 */
class ProfileFilePresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    /** The UI state of the screen. */
    val uiState: StateFlow<ProfileFileUiState> = services.machines.profile(profileId)
        .map { profile -> if (profile == null) ProfileFileUiState() else ProfileFileUiState(true, profile.name) }
        .stateIn(scope, SharingStarted.Eagerly, ProfileFileUiState())

    /** Writes the profile file through the document picker. */
    fun onExport() {
        scope.launch {
            val profile = services.machines.loadProfile(profileId) ?: return@launch
            val file = ProfileFile.export(profile, services.exportContext())
            val written = try {
                services.filePicker.createDocument(file.fileName, file.mimeType, file.bytes())
            } catch (error: Exception) {
                false
            }
            messages(if (written) UiText.of(Res.string.import_profile_exported, file.fileName) else UiText.of(Res.string.import_profile_export_failed))
        }
    }
}

/**
 * The profile file screen: the export of the SuperDRO profile file, and the import of a
 * SuperDRO profile file or of a TouchDRO settings file (`.tdset`) with a preview.
 */
@Composable
fun ProfileFileScreen(state: ProfileFileUiState, onExport: () -> Unit, onImport: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(title = stringResource(Res.string.import_profile_title), onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section(
                title = stringResource(Res.string.import_profile_export_title, state.profileName),
                text = stringResource(Res.string.import_profile_export_text),
                button = stringResource(Res.string.import_profile_export),
                icon = Res.drawable.ic_file_upload,
                enabled = state.loaded,
                onClick = onExport,
                tag = PROFILE_EXPORT_TAG,
            )
            Section(
                title = stringResource(Res.string.import_profile_import_title),
                text = stringResource(Res.string.import_profile_import_text),
                button = stringResource(Res.string.import_profile_import),
                icon = Res.drawable.ic_file_download,
                enabled = state.loaded,
                onClick = onImport,
                tag = PROFILE_IMPORT_TAG,
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    text: String,
    button: String,
    icon: org.jetbrains.compose.resources.DrawableResource,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(tag),
            ) {
                Icon(painterResource(icon), contentDescription = null)
                Text("  $button")
            }
        }
    }
}
