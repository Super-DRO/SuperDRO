package org.superdro.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one snackbar host of the frame: the tab status messages,
 * the undo of an offset change for 10 s, and the one-time hints that
 * do not block an action. [App] gives the value. A test gives its own host.
 */
val LocalAppSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }
