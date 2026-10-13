package org.superdro.core.ui.functions.common

import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.ScopedPresenter

/**
 * The presenter of the functions area for one tab. The tab owns it, so it lives while the tab
 * lives: a sheet or the side panel can close, and the undo of its last change stays.
 *
 * @property undo The undo offers of the stored data changes of the tab.
 */
class FunctionTabPresenter(val deviceId: DeviceId, val services: FunctionServices) : ScopedPresenter(services.context) {

    /** The ID of the machine profile of the tab. */
    val profileId: String get() = deviceId.value

    val undo: UndoOffers = UndoOffers(scope)

    companion object {
        /** The name of the presenter in the presenters of the tab. */
        const val NAME: String = "functions.tab"
    }
}

/** Gives the [FunctionTabPresenter] of the tab of this scope. */
fun TabEntryScope.functionTab(services: FunctionServices): FunctionTabPresenter =
    tabPresenter(FunctionTabPresenter.NAME) { FunctionTabPresenter(deviceId, services) }
