package org.superdro.core.ui.settings.keys

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_keys_added
import org.superdro.core.resources.settings_keys_removed
import org.superdro.core.resources.settings_keys_replaced
import org.superdro.core.resources.settings_keys_reset_done
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/**
 * One row of the key map.
 *
 * @property index The position of the mapping in the effective map.
 * @property key The name of the key.
 * @property action The text of the action with its axis.
 */
@Immutable
data class KeyMappingRow(val index: Int, val mapping: KeyMapping, val key: UiText, val action: UiText)

/**
 * The open "Add a key" dialog, or the capture of a key.
 *
 * @property replaceIndex The row whose key changes, or null for a new mapping.
 * @property capturing True while the "Press a key" dialog waits for a key.
 */
@Immutable
data class KeyDraft(
    val action: KeyAction = KeyAction.ZeroAxis,
    val axis: AxisId = AxisId.X,
    val replaceIndex: Int? = null,
    val capturing: Boolean = false,
) {
    /** True when the action of the draft needs an axis. */
    val needsAxis: Boolean get() = KeyMaps.needsAxis(action)
}

/** A message for the snackbar. [undo] is true while the undo of the last remove is available. */
@Immutable
data class KeyMessage(val id: Long, val text: UiText, val undo: Boolean = false)

/**
 * The state of the key mapping screen.
 *
 * @property customized False while the app uses its default map.
 */
@Immutable
data class KeyMappingUiState(
    val rows: List<KeyMappingRow> = emptyList(),
    val customized: Boolean = false,
    val draft: KeyDraft? = null,
    val message: KeyMessage? = null,
)

/**
 * The presenter of the key mapping screen. Each
 * change goes into [appSettings] at once. The first change writes the full map and sets
 * `keyMapCustomized`; "Reset to the default" clears it ([KeyMaps.effective]).
 *
 * Rules:
 * - A new mapping: the user selects the action (and the axis of an axis action), then presses the
 *   key. Escape or Back cancels the capture.
 * - A key that has a mapping already: the new mapping replaces the old one, and a message tells
 *   the old action and the new action.
 * - A remove has an undo for [UNDO_MS].
 */
class KeyMappingPresenter(
    private val appSettings: AppSettingsStore,
    context: PresenterContext,
) : ScopedPresenter(context), KeyMappingEvents {

    private val local = MutableStateFlow(Local())
    private var undoTimer: Job? = null
    private var nextId = 0L

    /** The state of the screen. */
    val uiState: StateFlow<KeyMappingUiState> = combine(appSettings.settings, local) { settings, state -> stateOf(settings, state) }
        .stateIn(scope, SharingStarted.Eagerly, stateOf(appSettings.settings.value, Local()))

    /** Opens the "Add a key" dialog. */
    override fun startAdd() {
        local.update { state -> state.copy(draft = KeyDraft()) }
    }

    /** Selects the action of the draft. */
    override fun setDraftAction(action: KeyAction) {
        if (action == KeyAction.None) return
        local.update { state -> state.copy(draft = state.draft?.copy(action = action)) }
    }

    /** Selects the axis of the draft. */
    override fun setDraftAxis(axis: AxisId) {
        local.update { state -> state.copy(draft = state.draft?.copy(axis = axis)) }
    }

    /** Opens the "Press a key" dialog of the draft. */
    override fun startCapture() {
        local.update { state -> state.copy(draft = state.draft?.copy(capturing = true)) }
    }

    /** Opens the "Press a key" dialog for a new key of the row [index]. */
    override fun startChangeKey(index: Int) {
        val mapping = KeyMaps.effective(appSettings.settings.value).getOrNull(index) ?: return
        local.update { state ->
            state.copy(draft = KeyDraft(action = mapping.action, axis = mapping.axis ?: AxisId.X, replaceIndex = index, capturing = true))
        }
    }

    /** Closes the dialog with no change. */
    override fun cancelDraft() {
        local.update { state -> state.copy(draft = null) }
    }

    /**
     * The key [keyCode] came while the "Press a key" dialog shows. A cancel key closes the dialog.
     * Each other key gets the action of the draft and goes into the map.
     */
    override fun onKeyCaptured(keyCode: Long) {
        val draft = local.value.draft?.takeIf { it.capturing } ?: return
        if (keyCode in KeyMaps.CANCEL_KEYS) {
            cancelDraft()
            return
        }
        val list = KeyMaps.effective(appSettings.settings.value).toMutableList()
        val mapping = KeyMapping(keyCode = keyCode, action = draft.action, axis = if (draft.needsAxis) draft.axis else null)
        val replaceAt = draft.replaceIndex?.takeIf { index -> index in list.indices }
        val duplicate = list.withIndex().firstOrNull { (index, old) -> old.keyCode == keyCode && index != replaceAt }
        if (replaceAt != null) list[replaceAt] = mapping else list.add(mapping)
        duplicate?.let { old -> list.removeAt(old.index) }
        write(list)
        val key = KeyMaps.keyText(keyCode)
        val newAction = KeyMaps.actionText(mapping)
        val old = duplicate?.value
        val text = if (old != null && (old.action != mapping.action || old.axis != mapping.axis)) {
            UiText.of(Res.string.settings_keys_replaced, key, KeyMaps.actionText(old), newAction)
        } else {
            UiText.of(Res.string.settings_keys_added, key, newAction)
        }
        local.update { state -> state.copy(draft = null, message = KeyMessage(++nextId, text), removed = null) }
    }

    /** Removes the row [index]. The snackbar has "Undo" for [UNDO_MS]. */
    override fun remove(index: Int) {
        val list = KeyMaps.effective(appSettings.settings.value).toMutableList()
        val mapping = list.getOrNull(index) ?: return
        list.removeAt(index)
        write(list)
        val id = ++nextId
        local.update { state ->
            state.copy(
                message = KeyMessage(id, UiText.of(Res.string.settings_keys_removed, KeyMaps.keyText(mapping.keyCode)), undo = true),
                removed = Removed(id, index, mapping),
            )
        }
        undoTimer?.cancel()
        undoTimer = scope.launch {
            delay(UNDO_MS)
            local.update { state -> if (state.removed?.id == id) state.copy(removed = null, message = state.message?.takeUnless { it.id == id }) else state }
        }
    }

    /** The undo of the remove of the message [id]: the mapping comes back at its old position. */
    fun undoRemove(id: Long) {
        val removed = local.value.removed?.takeIf { record -> record.id == id } ?: return
        val list = KeyMaps.effective(appSettings.settings.value).toMutableList()
        list.removeAll { mapping -> mapping.keyCode == removed.mapping.keyCode }
        list.add(removed.index.coerceIn(0, list.size), removed.mapping)
        write(list)
        undoTimer?.cancel()
        local.update { state -> state.copy(removed = null, message = null) }
    }

    /** "Reset to the default": the default map applies again. */
    override fun resetToDefault() {
        appSettings.update { settings -> settings.copy(keyMappings = emptyList(), keyMapCustomized = false) }
        local.update { state -> state.copy(draft = null, removed = null, message = KeyMessage(++nextId, UiText.of(Res.string.settings_keys_reset_done))) }
    }

    /** The snackbar showed the message [id]. */
    fun onMessageShown(id: Long) {
        local.update { state -> if (state.message?.id == id) state.copy(message = null) else state }
    }

    private fun write(map: List<KeyMapping>) {
        appSettings.update { settings -> settings.copy(keyMappings = map.toList(), keyMapCustomized = true) }
    }

    private fun stateOf(settings: AppSettings, state: Local): KeyMappingUiState {
        val map = KeyMaps.effective(settings)
        return KeyMappingUiState(
            rows = map.mapIndexed { index, mapping -> KeyMappingRow(index, mapping, KeyMaps.keyText(mapping.keyCode), KeyMaps.actionText(mapping)) },
            customized = settings.keyMapCustomized,
            draft = state.draft,
            message = state.message,
        )
    }

    private data class Removed(val id: Long, val index: Int, val mapping: KeyMapping)

    private data class Local(
        val draft: KeyDraft? = null,
        val message: KeyMessage? = null,
        val removed: Removed? = null,
    )

    companion object {
        /** The time of the undo of a remove: 10 s. */
        const val UNDO_MS: Long = 10_000L
    }
}
