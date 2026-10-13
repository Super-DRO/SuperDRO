package org.superdro.core.ui.readout.keypad

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.superdro.core.model.AxisId
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** A key of the keypad. */
enum class KeypadKey(val text: String) {
    Digit0("0"), Digit1("1"), Digit2("2"), Digit3("3"), Digit4("4"),
    Digit5("5"), Digit6("6"), Digit7("7"), Digit8("8"), Digit9("9"),
    Dot("."),

    /** `+`. */
    Plus("+"),

    /** `−`: it inserts `-`. */
    Minus("−"),

    /** `×`: it inserts `*`. */
    Times("×"),

    /** `÷`: it inserts `/`. */
    Divide("÷"),
    OpenParenthesis("("),
    CloseParenthesis(")"),

    /** `++x`: the text adds to the current value (relative entry). */
    RelativeAdd("++"),

    /** `--x`: the text subtracts from the current value. */
    RelativeSubtract("--"),
    Clear(""),
    Backspace(""),
    ;

    /** The text that the key inserts, or null for a key with an action. */
    val insert: String?
        get() = when (this) {
            Minus -> "-"
            Times -> "*"
            Divide -> "/"
            Clear, Backspace, RelativeAdd, RelativeSubtract -> null
            else -> text
        }

    companion object {
        /** The digits 0 to 9. */
        val digits: List<KeypadKey> = listOf(Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9)
    }
}

/**
 * The state of the keypad sheet.
 *
 * @property text The expression of the field.
 * @property result The live result of [text] in the display unit of the axis, formatted with the
 *   number pattern of the axis, or null for an empty text or an error.
 * @property error The error of the expression or the refusal of "Set", or null.
 */
@Immutable
data class KeypadUiState(
    val axis: AxisId,
    val text: String = "",
    val result: String? = null,
    val error: UiText? = null,
)

/**
 * The presenter of the keypad sheet of one axis. The keypad entry
 * owns it. The sheet shows the expression and the result before "Set". "Set" gives
 * the text to the [ReadoutPresenter]: `i(V)` in incremental mode, `h(V)` in absolute mode.
 *
 * The presenter has no coroutine: the evaluation is a pure function.
 */
class KeypadPresenter(val axis: AxisId, private val readout: ReadoutPresenter) : Presenter {

    private val mutable = MutableStateFlow(KeypadUiState(axis))

    /** The state of the sheet. */
    val uiState: StateFlow<KeypadUiState> = mutable.asStateFlow()

    /** The text of the field changed: a key of a physical keyboard, or a paste. */
    fun onTextChange(text: String) {
        mutable.value = evaluate(text)
    }

    /** A key of the sheet. */
    fun onKey(key: KeypadKey) {
        val text = mutable.value.text
        val next = when (key) {
            KeypadKey.Clear -> ""
            KeypadKey.Backspace -> text.dropLast(1)
            KeypadKey.RelativeAdd -> toggledPrefix(text, "++")
            KeypadKey.RelativeSubtract -> toggledPrefix(text, "--")
            else -> text + key.insert.orEmpty()
        }
        mutable.value = evaluate(next)
    }

    /**
     * "Set" (or Enter on a physical keyboard). Gives true when the value is set: the sheet closes.
     * Else the sheet shows the refusal.
     */
    fun onSet(): Boolean {
        val refusal = readout.onKeypadSet(axis, mutable.value.text)
        mutable.update { state -> state.copy(error = refusal) }
        return refusal == null
    }

    override fun close() = Unit

    private fun evaluate(text: String): KeypadUiState {
        val frame = readout.frame.value
        return when (val result = readout.keypadPreview(axis, text)) {
            null -> KeypadUiState(axis, text)
            is ExpressionResult.Value -> KeypadUiState(axis, text, result = frame?.pattern(axis)?.format(result.value) ?: result.text)
            is ExpressionResult.Error -> KeypadUiState(axis, text, error = result.error.toUiText())
        }
    }

    private fun toggledPrefix(text: String, prefix: String): String {
        val rest = text.removePrefix("++").removePrefix("--")
        return if (text.startsWith(prefix)) rest else prefix + rest
    }
}
