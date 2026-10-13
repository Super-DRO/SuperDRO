package org.superdro.core.ui.navigation

import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.presenter.PresenterOwner
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * The context of an entry of a tab back stack. The content lambda of a [TabRoute] entry gets it as
 * the receiver.
 *
 * @property deviceId The device of the tab: the ID of its machine profile.
 * @property navigator The back stack of the tab.
 * @property global The global back stack (the gear, the overflow menu, the add-device sheet).
 */
class TabEntryScope internal constructor(
    val deviceId: DeviceId,
    val navigator: TabNavigator,
    val global: GlobalNavigator,
    private val tabOwner: PresenterOwner,
    private val readoutFactory: (DeviceId) -> TabReadoutPresenter,
    private val messages: (UiText) -> Unit,
) {
    /**
     * The readout of the tab: the readout model, the live check, and the offset writes
     * with undo. Each entry of the tab gets the same object. The tab owns it, and it closes when
     * the tab goes away.
     */
    val readout: TabReadout get() = tabPresenter(READOUT_PRESENTER) { readoutFactory(deviceId) }

    /**
     * Gives the presenter of the tab with the name [name], or makes it with [create]. A
     * presenter of the tab lives until the tab goes away; a presenter of an entry
     * ([org.superdro.core.ui.presenter.rememberPresenter]) closes with its entry.
     */
    fun <P : Presenter> tabPresenter(name: String, create: () -> P): P = tabOwner.getOrCreate(name, create)

    /** Shows [text] in the snackbar of the frame. */
    fun showMessage(text: UiText) = messages(text)

    companion object {
        /** The name of the readout presenter in the presenters of the tab. */
        const val READOUT_PRESENTER: String = "readout"
    }
}

/** A presenter that gives the [TabReadout] of one tab. `core:di` binds a factory of it for each device. */
interface TabReadoutPresenter : TabReadout, Presenter

/**
 * The context of an entry of the global back stack.
 *
 * @property navigator The global back stack.
 * @property selectedDevice The device of the selected tab, or null.
 * @property tabNavigator Gives the back stack of the tab of a device, for example to open the
 *   machine settings of a tab from a global screen.
 */
class GlobalEntryScope internal constructor(
    val navigator: GlobalNavigator,
    val selectedDevice: DeviceId?,
    val tabNavigator: (DeviceId) -> TabNavigator?,
    private val messages: (UiText) -> Unit,
) {
    /** Shows [text] in the snackbar of the frame. */
    fun showMessage(text: UiText) = messages(text)
}
