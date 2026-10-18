package org.superdro.core.ui.functions.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.ThreadSetupRoute
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the thread setup.
 * [org.superdro.core.ui.functions.functionEntries] calls this function.
 */
internal fun EntryRegistry.threadEntries() {
    tab<ThreadSetupRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> ThreadSetupEntry(this) }
}

@Composable
private fun ThreadSetupEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { ThreadSetupPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val coroutines = rememberCoroutineScope()
    val events = remember(presenter, scope) {
        object : ThreadSetupEvents {
            override fun onRestore(id: String) = presenter.onRestore(id)
            override fun onMode(mode: ThreadToolMode) = presenter.onMode(mode)
            override fun onInternal(internal: Boolean) = presenter.onInternal(internal)
            override fun onLeftHand(leftHand: Boolean) = presenter.onLeftHand(leftHand)
            override fun onStarts(starts: Int) = presenter.onStarts(starts)
            override fun onForm(form: ThreadForm) = presenter.onForm(form)
            override fun onSize(size: String) = presenter.onSize(size)
            override fun onClass(threadClass: ThreadClass) = presenter.onClass(threadClass)
            override fun onCutter(cutter: ThreadCutter) = presenter.onCutter(cutter)
            override fun onInsertPreset(preset: PartialInsertPreset) = presenter.onInsertPreset(preset)
            override fun onInfeedMode(mode: ThreadInfeedMode) = presenter.onInfeedMode(mode)
            override fun onText(field: ThreadField, text: String) = presenter.onText(field, text)
            override fun onSyncStartZ() {
                presenter.onSyncStartZ()?.let(scope::showMessage)
            }
            override fun onCreate() {
                coroutines.launch {
                    val done = presenter.create() ?: return@launch
                    scope.showMessage(done)
                    scope.navigator.pop(ThreadSetupRoute)
                }
            }
            override fun onBack() = scope.navigator.pop(ThreadSetupRoute)
        }
    }
    ThreadSetupScreen(state, events)
}
