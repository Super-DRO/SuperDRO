package org.superdro.core.ui.readout.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.keypad_divisions_invalid
import org.superdro.core.resources.keypad_divisions_title
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.readout.keypad.ExpressionField
import org.superdro.core.ui.readout.keypad.KeypadGrid
import org.superdro.core.ui.readout.keypad.KeypadKey
import org.superdro.core.ui.readout.keypad.ResultLine
import org.superdro.core.ui.readout.keypad.SetButton
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.LocalTouchTargets

/** The state of the divisions keypad. [error] is the refusal of a value less than 1 or not a whole number. */
@Immutable
data class DivisionsUiState(val text: String = "", val error: UiText? = null)

/**
 * The presenter of the divisions keypad (`DivisionsRoute`). The sheet
 * starts with `round(360 / q)` = `N`, or an empty field when `N` is not set. "Set" needs a whole
 * number of 1 or more. It sets `N` and turns on the divisions mode.
 */
class DivisionsPresenter(private val readout: ReadoutPresenter) : Presenter {

    private val mutable = MutableStateFlow(DivisionsUiState(readout.divisionsValue()?.toString().orEmpty()))

    /** The state of the sheet. */
    val uiState: StateFlow<DivisionsUiState> = mutable.asStateFlow()

    /** The text of the field changed. */
    fun onTextChange(text: String) {
        mutable.value = DivisionsUiState(text.filter { character -> character.isDigit() })
    }

    /** A key of the sheet. Only the digits, Backspace, and Clear change the text. */
    fun onKey(key: KeypadKey) {
        val text = mutable.value.text
        mutable.value = DivisionsUiState(
            when (key) {
                KeypadKey.Clear -> ""
                KeypadKey.Backspace -> text.dropLast(1)
                in KeypadKey.digits -> text + key.text
                else -> text
            },
        )
    }

    /** "Set": gives true when `N` is set. */
    fun onSet(): Boolean {
        val divisions = mutable.value.text.toIntOrNull()
        val accepted = divisions != null && readout.onDivisionsSet(divisions)
        if (!accepted) mutable.value = mutable.value.copy(error = UiText.of(Res.string.keypad_divisions_invalid))
        return accepted
    }

    override fun close() = Unit
}

/** The keys of the divisions keypad: whole numbers only. */
private val DivisionRows: List<List<KeypadKey>> = listOf(
    listOf(KeypadKey.Digit7, KeypadKey.Digit8, KeypadKey.Digit9),
    listOf(KeypadKey.Digit4, KeypadKey.Digit5, KeypadKey.Digit6),
    listOf(KeypadKey.Digit1, KeypadKey.Digit2, KeypadKey.Digit3),
    listOf(KeypadKey.Clear, KeypadKey.Digit0, KeypadKey.Backspace),
)

/** The divisions keypad sheet. */
@Composable
fun DivisionsSheet(state: DivisionsUiState, onTextChange: (String) -> Unit, onKey: (KeypadKey) -> Unit, onSet: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.keypad_divisions_title)) {
        ExpressionField(state.text, onTextChange, onSet, label = stringResource(Res.string.keypad_divisions_title))
        Spacer(Modifier.height(8.dp))
        ResultLine(null, state.error)
        Spacer(Modifier.height(16.dp))
        KeypadGrid(rows = DivisionRows, onKey = onKey)
        Spacer(Modifier.height(LocalTouchTargets.current.gap))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            SetButton(onSet)
        }
    }
}
