package org.superdro.core.ui.functions.importing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.superdro.core.store.files.Report
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.ImportPreviewRoute
import org.superdro.core.ui.navigation.ProfileFileRoute
import org.superdro.core.ui.navigation.ReportRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the profile file export and import, the import preview, and the report screen.
 * [org.superdro.core.ui.functions.functionEntries] calls this function.
 */
internal fun EntryRegistry.importEntries() {
    tab<ImportPreviewRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> ImportPreviewEntry(this) }
    tab<ReportRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> ReportEntry(this) }
    tab<ProfileFileRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> ProfileFileEntry(this) }
}

@Composable
private fun ImportPreviewEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val flow = scope.importFlow(services)
    val environment = rememberResourceEnvironment()
    SideEffect { flow.environment = environment }
    val state by flow.uiState.collectAsState()
    // The end of the flow: the user cancelled the file picker, or the import finished. The
    // navigation is on the UI thread. A finished import with a report opens the report screen.
    LaunchedEffect(state.stage, state.report, state.done) {
        if (state.stage == ImportStage.Idle) {
            scope.navigator.pop(ImportPreviewRoute)
            return@LaunchedEffect
        }
        if (state.stage != ImportStage.Closed) return@LaunchedEffect
        val done = state.done
        val report = state.report
        flow.onClosed()
        if (done != null) scope.showMessage(done)
        if (report != null) {
            scope.reportHolder(services).show(ReportRequest(report, Report.IMPORT_REPORT_KIND, profileName(scope)))
            scope.navigator.replaceTop(ReportRoute)
        } else {
            scope.navigator.pop(ImportPreviewRoute)
        }
    }
    val events = remember(flow, scope) {
        object : ImportPreviewEvents {
            override fun onOperation(operation: ImportOperation) = flow.onOperation(operation)
            override fun onUnits(metric: Boolean) = flow.onUnits(metric)
            override fun onTarget(newProfile: Boolean) = flow.onTarget(newProfile)
            override fun onName(name: String) = flow.onName(name)
            override fun onConfirm() = flow.confirm()
            override fun onCancel() = flow.cancel()
        }
    }
    ImportPreviewScreen(state, events)
}

/** The name part of the report file: the name of the profile of the tab. */
private fun profileName(scope: TabEntryScope): String =
    scope.readout.frame.value?.profile?.name ?: scope.deviceId.value

@Composable
private fun ReportEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val holder = scope.reportHolder(services)
    val request by holder.report.collectAsState()
    ReportScreen(
        report = request?.report,
        onShare = { holder.share(scope::showMessage) },
        onBack = { scope.navigator.pop(ReportRoute) },
    )
}

@Composable
private fun ProfileFileEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { ProfileFilePresenter(scope.deviceId.value, services, scope::showMessage) }
    val state by presenter.uiState.collectAsState()
    ProfileFileScreen(
        state = state,
        onExport = presenter::onExport,
        onImport = { scope.startImport(services, ImportKind.Profile) },
        onBack = { scope.navigator.pop(ProfileFileRoute) },
    )
}
