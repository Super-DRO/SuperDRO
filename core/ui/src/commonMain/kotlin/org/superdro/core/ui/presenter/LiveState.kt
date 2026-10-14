package org.superdro.core.ui.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.StateFlow

/**
 * Collects a flow of a presenter that changes at the input rate, for example the value of an axis
 * or the state of the graphical view.
 *
 * Android collects only while the lifecycle is at least `STARTED`
 * (`collectAsStateWithLifecycle`). Thus a stopped activity does not recompose for each new value.
 * The JVM has no main dispatcher for the lifecycle, so it collects always (`collectAsState`).
 */
@Composable
expect fun <T> StateFlow<T>.collectLiveState(): State<T>
