package org.superdro.core.ui.readout.keypad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_backspace
import org.superdro.core.resources.keypad_backspace
import org.superdro.core.resources.keypad_clear
import org.superdro.core.resources.keypad_current
import org.superdro.core.resources.keypad_expression
import org.superdro.core.resources.keypad_mode_absolute
import org.superdro.core.resources.keypad_mode_incremental
import org.superdro.core.resources.keypad_relative_add
import org.superdro.core.resources.keypad_relative_subtract
import org.superdro.core.resources.keypad_result
import org.superdro.core.resources.keypad_set
import org.superdro.core.resources.keypad_title
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.readout.AxisValueUiState
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.readout.ReadoutTexts
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tag of the text field of the keypad. */
const val KEYPAD_FIELD_TAG: String = "keypad-field"

/** The test tag of the result line of the keypad. */
const val KEYPAD_RESULT_TAG: String = "keypad-result"

/** The test tag of the "Set" key. */
const val KEYPAD_SET_TAG: String = "keypad-set"

/** The test tag of a key. */
fun keyTag(key: KeypadKey): String = "keypad-key-${key.name}"

/** The keys of the keypad in rows. */
private val KeypadRows: List<List<KeypadKey>> = listOf(
    listOf(KeypadKey.Digit7, KeypadKey.Digit8, KeypadKey.Digit9, KeypadKey.Divide, KeypadKey.OpenParenthesis),
    listOf(KeypadKey.Digit4, KeypadKey.Digit5, KeypadKey.Digit6, KeypadKey.Times, KeypadKey.CloseParenthesis),
    listOf(KeypadKey.Digit1, KeypadKey.Digit2, KeypadKey.Digit3, KeypadKey.Minus, KeypadKey.RelativeAdd),
    listOf(KeypadKey.Digit0, KeypadKey.Dot, KeypadKey.Backspace, KeypadKey.Plus, KeypadKey.RelativeSubtract),
)

/**
 * The keypad sheet of one axis (`KeypadRoute`): the expression,
 * the result before "Set", and the keys. A physical keyboard types into the field; Enter is "Set".
 *
 * @param label The label of the axis, for example "X".
 * @param value The current value of the axis: the mode, the value, and the unit.
 */
@Composable
fun KeypadSheet(
    state: KeypadUiState,
    label: String,
    value: AxisValueUiState,
    onTextChange: (String) -> Unit,
    onKey: (KeypadKey) -> Unit,
    onSet: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.keypad_title, label)) {
        val unit = stringResource(ReadoutTexts.unitSymbol(value.unit))
        val mode = stringResource(if (value.incremental) Res.string.keypad_mode_incremental else Res.string.keypad_mode_absolute)
        Text(
            text = "$mode · ${stringResource(Res.string.keypad_current, value.text, unit)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ExpressionField(state.text, onTextChange, onSet)
        Spacer(Modifier.height(8.dp))
        ResultLine(state.result?.let { result -> stringResource(Res.string.keypad_result, result, unit) }, state.error)
        Spacer(Modifier.height(16.dp))
        KeypadGrid(rows = KeypadRows, onKey = onKey)
        Spacer(Modifier.height(LocalTouchTargets.current.gap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton(KeypadKey.Clear, onKey, wide = true)
            UnitKey(unit)
            Spacer(Modifier.weight(1f))
            SetButton(onSet)
        }
    }
}

/**
 * The text field of the expression. It keeps the cursor at the end after a key of the sheet.
 *
 * The keypad has its own keys, so the field never opens the soft keyboard of the system: the
 * soft keyboard covers the keys and "Set". The field asks for the focus
 * when it opens, so that a physical keyboard types into it at once. The key events of
 * a physical keyboard go to the focused field with no input method. Enter is "Set".
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ExpressionField(text: String, onTextChange: (String) -> Unit, onSet: () -> Unit, label: String = stringResource(Res.string.keypad_expression)) {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    InterceptPlatformTextInput(interceptor = NoSoftKeyboard) {
        OutlinedTextField(
            colors = accentTextFieldColors(),
            value = field,
            onValueChange = { next ->
                field = next
                if (next.text != text) onTextChange(next.text)
            },
            label = { Text(label) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSet() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(KEYPAD_FIELD_TAG)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onSet()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

/**
 * The interceptor of the expression field: it keeps each request of the input method and never
 * gives it to the platform, so the soft keyboard does not open. The request ends when the field
 * loses the focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val NoSoftKeyboard: PlatformTextInputInterceptor = PlatformTextInputInterceptor { _, _ -> awaitCancellation() }

/** The result before "Set", or the error in the error color. */
@Composable
internal fun ResultLine(result: String?, error: UiText?) {
    val text = error?.resolve() ?: result.orEmpty()
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(KEYPAD_RESULT_TAG),
    )
}

/** A grid of keys of 72 dp with gaps of 12 dp. */
@Composable
internal fun KeypadGrid(rows: List<List<KeypadKey>>, onKey: (KeypadKey) -> Unit) {
    val gap = LocalTouchTargets.current.gap
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { key -> KeyButton(key, onKey) }
            }
        }
    }
}

/** One key of the keypad. */
@Composable
internal fun KeyButton(key: KeypadKey, onKey: (KeypadKey) -> Unit, wide: Boolean = false) {
    val size = LocalTouchTargets.current.keypadKey
    val description = when (key) {
        KeypadKey.Backspace -> stringResource(Res.string.keypad_backspace)
        KeypadKey.RelativeAdd -> stringResource(Res.string.keypad_relative_add)
        KeypadKey.RelativeSubtract -> stringResource(Res.string.keypad_relative_subtract)
        KeypadKey.Clear -> stringResource(Res.string.keypad_clear)
        else -> key.text
    }
    val digit = key in KeypadKey.digits || key == KeypadKey.Dot
    DroButton(
        onClick = { onKey(key) },
        contentDescription = description,
        style = if (digit) DroButtonStyle.Neutral else DroButtonStyle.Primary,
        minWidth = if (wide) size * 2 else size,
        minHeight = size,
        modifier = Modifier.testTag(keyTag(key)),
    ) {
        when (key) {
            KeypadKey.Backspace -> Icon(painterResource(Res.drawable.ic_backspace), contentDescription = null, modifier = Modifier.size(28.dp))
            KeypadKey.Clear -> Text(stringResource(Res.string.keypad_clear), style = MaterialTheme.typography.titleMedium)
            else -> Text(key.text, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** The unit of the axis in the row of the keys. It is a label, not an action. */
@Composable
private fun UnitKey(unit: String) {
    val size = LocalTouchTargets.current.keypadKey
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(unit, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "Set" key. */
@Composable
internal fun SetButton(onSet: () -> Unit) {
    val size = LocalTouchTargets.current.keypadKey
    val text = stringResource(Res.string.keypad_set)
    DroButton(
        onClick = onSet,
        contentDescription = text,
        style = DroButtonStyle.Active,
        minWidth = size * 2,
        minHeight = size,
        modifier = Modifier.testTag(KEYPAD_SET_TAG),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}
