package org.superdro.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.FunctionMessage
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.expression.ExpressionError
import org.superdro.core.resources.Res
import org.superdro.core.resources.allStringResources
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.files.MessageKey

/**
 * A text of the UI that a presenter gives: a string resource with its arguments, or a plain text.
 * The composable resolves it in the language of the system with [resolve].
 *
 * The lower modules have no Compose resources. Their message types carry the name of the string
 * resource (the key) and the English text: `FunctionMessage`, `ReadoutMessage`, `ExpressionError`,
 * `StoreRefusal`, `MessageKey`, the camera texts, and others. [UiText.Key] maps such a key to its
 * resource in `core:resources` ([stringResourceForKey]). An unknown key shows the English text.
 */
@Immutable
sealed interface UiText {

    /** A string resource of `core:resources`. An argument can be a [UiText]: it resolves first. */
    data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText

    /**
     * A string resource with the name [key]. [fallback] is the English text, for a key that the
     * resources do not have. An argument can be a [UiText]: it resolves first.
     */
    data class Key(val key: String, val fallback: String, val args: List<Any> = emptyList()) : UiText

    /** A text that needs no translation, for example a machine name. */
    data class Plain(val text: String) : UiText

    companion object {
        /** Gives a [Resource] with [args]. */
        fun of(resource: StringResource, vararg args: Any): UiText = Resource(resource, args.toList())
    }
}

/** Gives the string resource with the name [key], or null when `core:resources` has no such string. */
fun stringResourceForKey(key: String): StringResource? = Res.allStringResources[key]

/** Gives the text in the language of the system. */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> stringResource(resource, *resolveArgs(args))
    is UiText.Key -> {
        val resource = stringResourceForKey(key)
        if (resource == null) {
            ReadoutMessage.formatText(fallback, resolveArgs(args).toList())
        } else {
            stringResource(resource, *resolveArgs(args))
        }
    }
}

@Composable
private fun resolveArgs(args: List<Any>): Array<Any> = Array(args.size) { index ->
    when (val argument = args[index]) {
        is UiText -> argument.resolve()
        else -> argument
    }
}

/** The text of a function message of `core:functions`. A [FunctionMessage] argument resolves with its own key. */
fun FunctionMessage.toUiText(): UiText =
    UiText.Key(key, template.template, args.map { argument -> if (argument is FunctionMessage) argument.toUiText() else argument })

/** The text of a readout message of `core:readout`. */
fun ReadoutMessage.toUiText(): UiText = UiText.Key(text.key, text.english, args)

/** The text of an error of the expression evaluator. */
fun ExpressionError.toUiText(): UiText = UiText.Key(key, message, arguments)

/** The text of a refusal of the store, for example "A profile with this name already exists.". */
fun StoreRefusal.toUiText(): UiText = UiText.Key(stringKey, message)

/** The text of an import or export message of `core:store`. */
fun MessageKey.toUiText(vararg args: Any): UiText = UiText.Key(resourceName, english, args.toList())
