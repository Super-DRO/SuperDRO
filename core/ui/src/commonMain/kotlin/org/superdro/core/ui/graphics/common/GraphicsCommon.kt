package org.superdro.core.ui.graphics.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.readout.UnitConverter
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_unit_inch
import org.superdro.core.resources.readout_unit_mm
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors

/** The length texts of the graphics area. Lengths are µm. */
object LengthTexts {

    /** Gives [um] in mm or inch with the decimals of the readout format [format], for example "12.700". */
    fun format(um: Double, metric: Boolean, format: ReadoutFormat): String =
        ReadoutPatterns.linear(format, metric).format(UnitConverter.linear(metric).toDisplay(um))

    /** Gives the µm of the display value [display] in mm or inch. */
    fun toUm(display: Double, metric: Boolean): Double = UnitConverter.linear(metric).toInternal(display)

    /** Gives the display value of [um] in mm or inch. */
    fun toDisplay(um: Double, metric: Boolean): Double = UnitConverter.linear(metric).toDisplay(um)

    /** The unit symbol "mm" or "in". */
    fun unitSymbol(metric: Boolean): StringResource = if (metric) Res.string.readout_unit_mm else Res.string.readout_unit_inch
}

/** The result of the evaluation of a length field. */
sealed interface LengthInput {
    /** The field is empty. */
    data object Empty : LengthInput

    /** The field gives [um]. */
    data class Value(val um: Double) : LengthInput

    /** The field has an error of the expression evaluator. */
    data class Invalid(val error: UiText) : LengthInput
}

/**
 * Evaluates the length field [text] in mm or inch. `++x` and `--x` add to and subtract from
 * [currentUm] (the absolute position).
 */
fun evaluateLength(text: String, metric: Boolean, currentUm: Double = 0.0): LengthInput {
    if (text.isBlank()) return LengthInput.Empty
    val current = LengthTexts.toDisplay(currentUm, metric)
    return when (val result = ExpressionEvaluator.evaluate(text.trim(), if (current.isFinite()) current else 0.0)) {
        is ExpressionResult.Value -> LengthInput.Value(LengthTexts.toUm(result.value, metric))
        is ExpressionResult.Error -> LengthInput.Invalid(result.error.toUiText())
    }
}

/**
 * A text field of a value with its unit and an optional sync button that copies the live value.
 * The field has the accent colors of the graphics area.
 *
 * @param error The error text under the field, or null.
 * @param onSync The sync button, or null for no button.
 * @param fieldModifier The modifier of the text field itself, for example a test tag.
 */
@Composable
fun ValueField(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    unit: String?,
    modifier: Modifier = Modifier,
    error: UiText? = null,
    syncLabel: String? = null,
    onSync: (() -> Unit)? = null,
    enabled: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(label) },
            suffix = unit?.let { symbol -> { Text(symbol) } },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message.resolve()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = accentTextFieldColors(),
            modifier = Modifier.weight(1f).then(fieldModifier),
        )
        if (onSync != null && syncLabel != null) {
            OutlinedButton(
                onClick = onSync,
                enabled = enabled,
                colors = AccentButtons.outlined(),
                modifier = Modifier.padding(start = 8.dp).heightIn(min = LocalTouchTargets.current.button),
            ) { Text(syncLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Gives the text of a [StringResource] of a unit for [metric]. */
@Composable
fun unitText(metric: Boolean): String = stringResource(LengthTexts.unitSymbol(metric))
