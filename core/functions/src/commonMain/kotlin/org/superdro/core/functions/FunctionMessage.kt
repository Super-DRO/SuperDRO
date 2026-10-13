package org.superdro.core.functions

/**
 * One UI text of a machine function: a message, a label, or a dialog title.
 *
 * The module has no Compose resources. Thus each text has the name of its string in
 * `core:resources` ([key]) and the English value of that string ([template]). The UI gets the
 * translated string with [key]. The tests and the logs use [FunctionMessage.text].
 *
 * The template uses the format specifiers of a string resource: `%1$s`, `%2$s`, and `%1$d`.
 */
data class MessageTemplate(val key: String, val template: String) {

    /** Gives a message with the arguments [args] for the format specifiers, in sequence. */
    fun format(vararg args: Any): FunctionMessage = FunctionMessage(this, args.toList())

    /** Gives a message with no arguments. */
    fun message(): FunctionMessage = FunctionMessage(this)
}

/**
 * A [MessageTemplate] with its arguments. An argument is a [String], a number, or a different
 * [FunctionMessage] (for example the label of a field). The UI translates a [FunctionMessage]
 * argument with its own key.
 */
data class FunctionMessage(val template: MessageTemplate, val args: List<Any> = emptyList()) {

    /** The name of the string resource. */
    val key: String get() = template.key

    /** The English text with the arguments in place. */
    val text: String get() = render(template.template, args)

    override fun toString(): String = text

    private companion object {
        val SPECIFIER = Regex("""%(\d+)\$([sd])""")

        fun render(template: String, args: List<Any>): String = SPECIFIER.replace(template) { match ->
            val index = match.groupValues[1].toInt() - 1
            val argument = args.getOrNull(index)
            when (argument) {
                null -> match.value
                is FunctionMessage -> argument.text
                else -> argument.toString()
            }
        }
    }
}
