package org.superdro.app.adapterprobe.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The text of a field whose value comes back from a presenter flow. The presenter state
 * arrives later than the key events, so a fast input gets old values back. A value that the field sent itself is an
 * echo: it does not change the text. A different value, for example the stored host after the start, replaces it.
 */
internal class FieldText(initial: String) {

    /** The text that the field shows. */
    var text: String by mutableStateOf(initial)
        private set

    private val sent = ArrayDeque<String>()

    /** Records an edit of the user: the field shows [value] at once. */
    fun edit(value: String) {
        text = value
        sent.addLast(value)
        if (sent.size > MAX_SENT) sent.removeFirst()
    }

    /** Receives the value of the presenter state. */
    fun external(value: String) {
        val index = sent.indexOf(value)
        if (index >= 0) {
            repeat(index + 1) { sent.removeFirst() }
            return
        }
        if (value != text) {
            text = value
            sent.clear()
        }
    }

    private companion object {
        const val MAX_SENT = 64
    }
}

/** An `OutlinedTextField` for a value of a presenter flow. It keeps the text of a fast input (see [FieldText]). */
@Composable
internal fun ProbeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val field = remember { FieldText(value) }
    LaunchedEffect(value) { field.external(value) }
    OutlinedTextField(
        value = field.text,
        onValueChange = { text ->
            field.edit(text)
            onValueChange(text)
        },
        label = label,
        colors = probeTextFieldColors(),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}
