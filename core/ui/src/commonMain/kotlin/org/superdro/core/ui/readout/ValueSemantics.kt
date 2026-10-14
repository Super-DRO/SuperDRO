package org.superdro.core.ui.readout

import androidx.compose.runtime.Immutable

/**
 * The three templates of the spoken text of a readout value, from the string resources:
 * `readout_value_semantics`, `readout_state_semantics`, and `readout_value_state_semantics`.
 */
@Immutable
data class ValueSemanticsTemplates(val value: String, val state: String, val valueState: String)

/**
 * The spoken text of a readout value. The value box reads the templates one time. It makes the
 * text only when the semantics need it, so that a new value reads no string resource.
 */
object ValueSemantics {

    /** The placeholders `%1$s` and `%1$d` of a string resource. Compose Resources uses the same expression. */
    private val Placeholder = Regex("""%(\d+)\$[ds]""")

    /**
     * Gives the spoken text of a value. An error and "no input" give the label and the state.
     * Each other state gives the label, the value, and the unit name, and then the state text if
     * there is one.
     */
    fun description(
        templates: ValueSemanticsTemplates,
        label: String,
        state: ValueState,
        text: String,
        unitName: String,
        stateText: String?,
    ): String {
        if (state == ValueState.Error || state == ValueState.NoInput) return format(templates.state, label, stateText.orEmpty())
        val valueText = format(templates.value, label, text, unitName)
        return if (stateText == null) valueText else format(templates.valueState, valueText, stateText)
    }

    /** Puts [args] into the placeholders of [template], as `stringResource(resource, args)` does. */
    fun format(template: String, vararg args: String): String =
        Placeholder.replace(template) { match -> args[match.groupValues[1].toInt() - 1] }
}
