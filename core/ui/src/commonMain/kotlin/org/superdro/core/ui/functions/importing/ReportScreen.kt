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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_share
import org.superdro.core.resources.import_report_empty
import org.superdro.core.resources.import_report_share
import org.superdro.core.resources.import_report_share_failed
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.Report
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the share button of the report screen. */
const val REPORT_SHARE_TAG: String = "import-report-share"

/**
 * The report that the report screen shows.
 *
 * @property kind The kind of the file name of the text export, for example `import-report`.
 * @property name The name part of the file name, for example the profile name.
 */
@Immutable
data class ReportRequest(val report: Report, val kind: String = Report.IMPORT_REPORT_KIND, val name: String = "")

/**
 * The report of a tab: the import report and the error map report of
 * the adapter area use the same screen. The tab owns the presenter.
 */
class ReportPresenter(private val services: FunctionServices) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow<ReportRequest?>(null)

    /** The report of the screen, or null. */
    val report: StateFlow<ReportRequest?> = mutable.asStateFlow()

    /** Sets the report of the screen. */
    fun show(request: ReportRequest) {
        mutable.value = request
    }

    /** The text export file of the report: `superdro-<kind>-<name>-<yyyyMMdd_HHmmss>.txt`. */
    fun exportFile(): ExportFile? = mutable.value?.let { request ->
        request.report.exportFile(request.kind, request.name, services.exportContext())
    }

    /** Opens the share sheet with the text of the report. [messages] shows a failure. */
    fun share(messages: (UiText) -> Unit) {
        val file = exportFile() ?: return
        scope.launch {
            val opened = try {
                services.filePicker.share(file.fileName, file.mimeType, file.bytes())
            } catch (error: Exception) {
                false
            }
            if (!opened) messages(UiText.of(Res.string.import_report_share_failed))
        }
    }

    companion object {
        /** The name of the presenter in the presenters of the tab. */
        const val NAME: String = "functions.report"
    }
}

/**
 * The report screen: the title, the summary, the
 * sections, and the text export through the share sheet.
 */
@Composable
fun ReportScreen(report: Report?, onShare: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val shareText = stringResource(Res.string.import_report_share)
    ScreenScaffold(
        title = report?.title.orEmpty(),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = onShare, enabled = report != null) {
                Icon(painterResource(Res.drawable.ic_share), contentDescription = shareText)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (report == null) {
                Text(stringResource(Res.string.import_report_empty), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(report.summary, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            report.sections.forEach { section ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(section.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FilledTonalButton(
                onClick = onShare,
                modifier = Modifier.align(Alignment.End).heightIn(min = LocalTouchTargets.current.button).testTag(REPORT_SHARE_TAG),
            ) {
                Icon(painterResource(Res.drawable.ic_share), contentDescription = null)
                Text("  $shareText")
            }
        }
    }
}
