package org.superdro.core.ui.functions.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_undo
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.stringResourceForKey

/** One undo that the user can still do. [id] changes for each new offer. */
@Immutable
data class PendingUndo(val id: Long, val label: UiText)

/**
 * The undo of a change of stored data of the functions area: the snackbar
 * of the frame shows [PendingUndo.label] with "Undo" for [durationMs]. A new offer replaces the
 * old offer.
 *
 * The object runs in the scope of a presenter of the tab ([FunctionTabPresenter]), so the
 * snackbar stays when a sheet or the side panel closes. A composable of the area attaches the
 * snackbar host of the frame with [AttachUndoHost]. With no host (a presenter test), [pending]
 * still tells the offer, and [undo] does the undo.
 */
class UndoOffers(private val scope: CoroutineScope, private val durationMs: Long = UNDO_MS) {

    private val mutable = MutableStateFlow<PendingUndo?>(null)
    private var action: (suspend () -> Unit)? = null
    private var host: SnackbarHostState? = null
    private var environment: ResourceEnvironment? = null
    private var timer: Job? = null
    private var snackbar: Job? = null
    private var nextId = 0L

    /** The offer that the user can still take, or null. */
    val pending: StateFlow<PendingUndo?> = mutable.asStateFlow()

    /**
     * Gives the snackbar host of the frame and the resource environment of the texts. The texts
     * of the snackbar load outside of a composition, so they need the environment of a composition.
     */
    fun attach(host: SnackbarHostState, environment: ResourceEnvironment) {
        this.host = host
        this.environment = environment
    }

    /**
     * Offers the undo [undo] with the text [label] for [durationMs]. Gives the ID of the offer.
     * [undo] runs in the scope of the presenter.
     */
    fun offer(label: UiText, undo: suspend () -> Unit): Long {
        val id = ++nextId
        clear()
        action = undo
        mutable.value = PendingUndo(id, label)
        timer = scope.launch {
            delay(durationMs)
            if (mutable.value?.id == id) clear()
        }
        host?.let { current -> snackbar = scope.launch { show(current, id, label) } }
        return id
    }

    /** Does the undo of the offer [id]. Gives false when the offer is not current (it timed out or a new offer came). */
    fun undo(id: Long): Boolean {
        if (mutable.value?.id != id) return false
        val current = action
        clear()
        if (current != null) scope.launch { current() }
        return true
    }

    /** Removes the offer [id] with no undo. */
    fun dismiss(id: Long) {
        if (mutable.value?.id == id) clear()
    }

    private suspend fun show(host: SnackbarHostState, id: Long, label: UiText) {
        val text = label.loadString(environment)
        val actionText = UiText.of(Res.string.fn_undo).loadString(environment)
        val result = host.showSnackbar(message = text, actionLabel = actionText, withDismissAction = false, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) undo(id) else dismiss(id)
    }

    private fun clear() {
        timer?.cancel()
        timer = null
        // A cancel of the job of showSnackbar removes the snackbar.
        snackbar?.cancel()
        snackbar = null
        action = null
        mutable.value = null
    }

    companion object {
        /** The undo shows for 10 s. */
        const val UNDO_MS: Long = 10_000L
    }
}

/** Gives the snackbar host of the frame to [offers]. Put it in each entry of the area that offers an undo. */
@Composable
fun AttachUndoHost(offers: UndoOffers) {
    val host = LocalAppSnackbarHostState.current
    val environment = rememberResourceEnvironment()
    SideEffect { offers.attach(host, environment) }
}

/**
 * Gives the text in the language of [environment], outside of a composition (for example for the
 * snackbar of a presenter). A composable gives the environment with `rememberResourceEnvironment()`.
 * With no environment, the function uses the environment of the system; on the JVM this needs a
 * display.
 */
suspend fun UiText.loadString(environment: ResourceEnvironment? = null): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> load(environment, resource, loadArgs(args, environment))
    is UiText.Key -> {
        val resource = stringResourceForKey(key)
        val values = loadArgs(args, environment)
        if (resource == null) ReadoutMessage.formatText(fallback, values.toList()) else load(environment, resource, values)
    }
}

private suspend fun load(environment: ResourceEnvironment?, resource: StringResource, args: Array<Any>): String =
    if (environment == null) getString(resource, *args) else getString(environment, resource, *args)

private suspend fun loadArgs(args: List<Any>, environment: ResourceEnvironment?): Array<Any> {
    val values = ArrayList<Any>(args.size)
    for (argument in args) values += if (argument is UiText) argument.loadString(environment) else argument
    return values.toTypedArray()
}
