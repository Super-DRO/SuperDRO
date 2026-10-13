package org.superdro.composite.util

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.w3c.dom.Element

/**
 * Reads and examines one string resource file, for example `values-de/strings_app.xml`.
 *
 * The rules here decide if the localization check passes. Thus `StringResourceTest.kt` has unit
 * tests for them.
 */
object StringResource {

    /**
     * Finds a Java format specifier, for example `%1$s`, `%d`, or the literal `%%`.
     *
     * The flag characters do not include the space flag that `java.util.Formatter` accepts.
     * French and German typography puts a space before a percent sign, so `100 % de la batterie`
     * would otherwise read as the specifier `% d`. The space flag has no use in a string
     * resource, and a wrong result here fails the build for a correct translation.
     *
     * The conversion characters are the set that `java.util.Formatter` accepts. A letter that is
     * not in that set, for example the `z` of `100%zip`, is not a specifier.
     */
    private val FORMAT_SPECIFIER =
        Regex("""%%|%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtTn])""")

    /** One format specifier. [index] is null when the specifier gives no argument position. */
    data class Specifier(val index: Int?, val conversion: Char) {
        override fun toString(): String = if (index == null) "%$conversion" else "%$index\$$conversion"
    }

    /**
     * The resources of one file.
     *
     * - [strings]: the name and the value of each `<string>` element.
     * - [plurals]: the name of each `<plurals>` element, with the quantity and the value of each
     *   `<item>`.
     * - [arrays]: the name and the items of each `<string-array>` element.
     * - [duplicates]: each name that the file uses more than one time, for all three kinds.
     */
    class Parsed(
        val strings: Map<String, String>,
        val duplicates: List<String>,
        val plurals: Map<String, Map<String, String>> = emptyMap(),
        val arrays: Map<String, List<String>> = emptyMap(),
    ) {
        /** All resource names of the file, in the order strings, plurals, arrays. */
        val names: Set<String> get() = strings.keys + plurals.keys + arrays.keys

        /**
         * Each value of the file with a label. A string gives `name`, a plural item gives
         * `name[quantity]`, and an array item gives `name[index]`.
         */
        fun labelledValues(): Map<String, String> = buildMap {
            putAll(strings)
            plurals.forEach { (name, items) -> items.forEach { (quantity, value) -> put("$name[$quantity]", value) } }
            arrays.forEach { (name, items) -> items.forEachIndexed { index, value -> put("$name[$index]", value) } }
        }
    }

    /**
     * Reads [file] and gives each resource in file order.
     *
     * A repeated name is a defect: the last value wins, and the earlier value goes away with no
     * message. Thus [Parsed.duplicates] gives each repeated name.
     */
    fun read(file: File): Parsed {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // The input is a file of this repository. But a doctype declaration gives an XML parser
        // an external entity to get. Refuse it.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = try {
            factory.newDocumentBuilder().parse(file)
        } catch (error: Exception) {
            throw GradleException("Cannot parse ${file.path}: ${error.message}", error)
        }
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        fun record(name: String) {
            if (!seen.add(name)) duplicates += name
        }

        val strings = LinkedHashMap<String, String>()
        val stringNodes = document.getElementsByTagName("string")
        for (index in 0 until stringNodes.length) {
            val element = stringNodes.item(index) as Element
            val name = element.getAttribute("name")
            record(name)
            strings[name] = element.textContent
        }

        val plurals = LinkedHashMap<String, Map<String, String>>()
        val pluralNodes = document.getElementsByTagName("plurals")
        for (index in 0 until pluralNodes.length) {
            val element = pluralNodes.item(index) as Element
            val name = element.getAttribute("name")
            record(name)
            val items = LinkedHashMap<String, String>()
            val itemNodes = element.getElementsByTagName("item")
            for (itemIndex in 0 until itemNodes.length) {
                val item = itemNodes.item(itemIndex) as Element
                items[item.getAttribute("quantity")] = item.textContent
            }
            plurals[name] = items
        }

        val arrays = LinkedHashMap<String, List<String>>()
        val arrayNodes = document.getElementsByTagName("string-array")
        for (index in 0 until arrayNodes.length) {
            val element = arrayNodes.item(index) as Element
            val name = element.getAttribute("name")
            record(name)
            val itemNodes = element.getElementsByTagName("item")
            arrays[name] = (0 until itemNodes.length).map { itemIndex -> itemNodes.item(itemIndex).textContent }
        }
        return Parsed(strings, duplicates, plurals, arrays)
    }

    /**
     * Gives the format specifiers in [value], in the order that they show. A literal `%%`
     * prints a percent sign and takes no argument, so the list does not include it.
     */
    fun formatSpecifiers(value: String): List<Specifier> = FORMAT_SPECIFIER
        .findAll(value)
        .filterNot { match -> match.value == "%%" }
        .map { match ->
            Specifier(
                index = match.groupValues[1].toIntOrNull(),
                conversion = match.groupValues[2].first()
            )
        }
        .toList()

    /**
     * Tells if AAPT2 refuses [value]. A value in double quotes keeps its apostrophes. Each other
     * apostrophe needs a backslash before it.
     *
     * The double quotes must enclose the full value. `"a" and 'b'` starts and ends with a double
     * quote, but the quotes do not enclose the full value, so its apostrophe is an error.
     */
    fun hasUnescapedApostrophe(value: String): Boolean {
        var index = 0
        var quoted = false
        while (index < value.length) {
            when (value[index]) {
                '\\' -> index += 2
                '"' -> {
                    quoted = !quoted
                    index += 1
                }
                '\'' -> if (quoted) index += 1 else return true
                else -> index += 1
            }
        }
        return false
    }

    /**
     * Tells if [value] has `\'` or `\"`. Compose Multiplatform resources do not remove these
     * backslashes, so the UI shows them. A file that only Compose reads must use a plain
     * apostrophe and a plain quote.
     */
    fun hasBackslashQuote(value: String): Boolean = value.contains("\\'") || value.contains("\\\"")

    /**
     * Tells if [value] contains words. A value with only format specifiers, digits, or
     * punctuation, for example `%1$d/%2$d` or `+`, is the same in each locale. Thus the check
     * must not report it as untranslated.
     */
    fun isTranslatable(value: String): Boolean =
        FORMAT_SPECIFIER.replace(value, "").any { character -> character.isLetter() }
}
