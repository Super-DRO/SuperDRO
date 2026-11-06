package org.superdro.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf

/**
 * The one snackbar host of the frame: the tab status messages,
 * the undo of an offset change for 10 s, and the one-time hints that
 * do not block an action. [App] gives the value. A test gives its own host.
 */
val LocalAppSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/**
 * The controls at the bottom of the frame content that the snackbar must not cover, for example
 * the side panel tabs and the zoom presets. Each control tells the top of its bounds in root
 * pixels. [FrameLayout] puts the snackbar above the highest of these tops.
 */
@Stable
class SnackbarClearance {
    private val tops = mutableStateMapOf<Any, Float>()

    /** The smallest top of the controls in root pixels, or null when no control is shown. */
    val top: Float? get() = tops.values.minOrNull()

    internal fun report(key: Any, top: Float) {
        if (tops[key] != top) tops[key] = top
    }

    internal fun remove(key: Any) {
        tops.remove(key)
    }
}

/** The clearance of the frame. [FrameLayout] gives the value. */
val LocalSnackbarClearance = staticCompositionLocalOf { SnackbarClearance() }

/** Keeps the snackbar of the frame above this control. */
fun Modifier.keepClearOfSnackbar(): Modifier = this then KeepClearOfSnackbarElement

private data object KeepClearOfSnackbarElement : ModifierNodeElement<KeepClearOfSnackbarNode>() {
    override fun create(): KeepClearOfSnackbarNode = KeepClearOfSnackbarNode()
    override fun update(node: KeepClearOfSnackbarNode) = Unit
}

private class KeepClearOfSnackbarNode :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {

    private var clearance: SnackbarClearance? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val current = currentValueOf(LocalSnackbarClearance)
        if (clearance !== current) {
            clearance?.remove(this)
            clearance = current
        }
        if (coordinates.isAttached) {
            current.report(this, coordinates.boundsInRoot().top)
        }
    }

    override fun onDetach() {
        clearance?.remove(this)
        clearance = null
    }
}
