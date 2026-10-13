package org.superdro.composite.util

/**
 * Compares the string files of each translation with the string files of the base locale.
 *
 * SuperDRO has one string file for each work area, for example `values/strings_app.xml` and
 * `values/strings_readout.xml`. Each locale directory must have a file with the same name and the
 * same resource names (`CLAUDE.md`, "Strings and languages").
 *
 * The comparison puts each result in one of two classes:
 *
 * - **An error fails the build.**
 *   - An expected locale directory with no string file.
 *   - A base file that a locale does not have, or a locale file that the base does not have.
 *   - A name that the base file has and the locale file does not have, or the opposite.
 *   - A name in more than one file of one locale, or two times in one file.
 *   - A format specifier that asks for an argument that the base value does not give, or a
 *     conversion character that does not agree. These throw an exception at run time.
 *   - In a file that the apps copy into the Android resources ([androidResourceFiles]): an
 *     apostrophe with no escape in the base locale. AAPT2 refuses the base file.
 *   - In each other file: `\'` or `\"` in any locale. Only Compose reads these files, and
 *     Compose does not remove the backslash, so the UI shows it.
 *   - More than one format specifier in a base value when one of them has no argument
 *     position. A translation cannot put such specifiers in a different order.
 *   - An entry of the allow list that names no base resource.
 * - **A warning does not fail the build.** A value that is still the English text, an
 *   apostrophe with no escape in a translation, and an argument that a translation does not use.
 *
 * The function has no Gradle types, so that a unit test can examine it.
 */
object LocalizationComparison {

    /** The result. [errors] fails the build. [warnings] holds the notes of each locale. */
    class Result(val errors: List<String>, val warnings: Map<String, List<String>>)

    /**
     * @param baseLocale The name of the base directory, `values`.
     * @param base The files of the base directory: file name to content.
     * @param translations The files of each translation directory: directory name to (file name
     *   to content). A directory with no string file is not in this map.
     * @param expectedLocales The directory name of each locale that the app ships, for example
     *   `values-de`.
     * @param allowList The names that stay in English. A bare name covers each locale. The form
     *   `<locale>:<name>` covers one locale.
     * @param androidResourceFiles The names of the files that the apps copy into the Android
     *   resources, for example `strings_app.xml`. AAPT2 reads these files. Only Compose reads the
     *   other files.
     */
    fun compare(
        baseLocale: String,
        base: Map<String, StringResource.Parsed>,
        translations: Map<String, Map<String, StringResource.Parsed>>,
        expectedLocales: Set<String>,
        allowList: Set<String>,
        androidResourceFiles: Set<String> = emptySet(),
    ): Result {
        val errors = mutableListOf<String>()
        val warnings = sortedMapOf<String, MutableList<String>>()

        checkLocaleNames(baseLocale, base, errors)
        base.toSortedMap().forEach { (fileName, parsed) ->
            parsed.plurals.forEach { (name, items) ->
                if ("other" !in items) {
                    errors += "$baseLocale/$fileName: $name has no item with the quantity `other`."
                }
            }
            parsed.labelledValues().forEach { (label, value) ->
                if (fileName in androidResourceFiles) {
                    if (StringResource.hasUnescapedApostrophe(value)) {
                        errors += "$baseLocale/$fileName: $label has an apostrophe with no escape."
                    }
                } else if (StringResource.hasBackslashQuote(value)) {
                    errors += "$baseLocale/$fileName: $label has a backslash before a quote. Compose shows the " +
                        "backslash. Use a plain apostrophe or quote."
                }
                val specifiers = StringResource.formatSpecifiers(value)
                if (specifiers.size > 1 && specifiers.any { specifier -> specifier.index == null }) {
                    errors += "$baseLocale/$fileName: $label has more than one format specifier, and one or " +
                        "more of them give no argument position. A translation cannot put these in a different order."
                }
            }
        }
        val baseNames = base.values.flatMap { parsed -> parsed.names }.toSet()
        allowList.forEach { entry ->
            val name = entry.substringAfterLast(':')
            if (name !in baseNames) {
                errors += "untranslatedAllowList names $entry, which the base locale does not have."
            }
        }

        expectedLocales.sorted().forEach { locale ->
            if (translations[locale].isNullOrEmpty()) {
                errors += "$locale: the directory has no string file."
            }
        }

        translations.toSortedMap().forEach { (locale, files) ->
            val notes = warnings.getOrPut(locale) { mutableListOf() }
            if (locale !in expectedLocales) {
                notes += "$locale is not an expected locale"
            }
            checkLocaleNames(locale, files, errors)
            (base.keys - files.keys).sorted().forEach { fileName ->
                errors += "$locale: $fileName is absent. Each file of $baseLocale must have a translation."
            }
            (files.keys - base.keys).sorted().forEach { fileName ->
                errors += "$locale: $fileName has no file of the same name in $baseLocale."
            }
            files.toSortedMap().forEach { (fileName, translation) ->
                val baseFile = base[fileName] ?: return@forEach
                compareFile(
                    locale, fileName, baseFile, translation, allowList, fileName in androidResourceFiles, errors, notes
                )
            }
        }
        return Result(errors, warnings.filterValues { notes -> notes.isNotEmpty() })
    }

    /** Reports a name that one locale uses two times, in one file or in two files. */
    private fun checkLocaleNames(
        locale: String,
        files: Map<String, StringResource.Parsed>,
        errors: MutableList<String>,
    ) {
        val owner = mutableMapOf<String, String>()
        files.toSortedMap().forEach { (fileName, parsed) ->
            parsed.duplicates.forEach { name -> errors += "$locale/$fileName: $name shows more than one time." }
            parsed.names.forEach { name ->
                val other = owner.put(name, fileName)
                if (other != null && other != fileName) {
                    errors += "$locale: $name is in $other and in $fileName."
                }
            }
        }
    }

    private fun compareFile(
        locale: String,
        fileName: String,
        base: StringResource.Parsed,
        translation: StringResource.Parsed,
        allowList: Set<String>,
        androidResource: Boolean,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        (base.names - translation.names).forEach { name ->
            errors += "$locale/$fileName: $name is absent."
        }
        (translation.names - base.names).forEach { name ->
            errors += "$locale/$fileName: $name is not in the base file."
        }
        base.plurals.keys.forEach { name ->
            val translated = translation.plurals[name] ?: return@forEach
            if ("other" !in translated) {
                errors += "$locale/$fileName: $name has no item with the quantity `other`."
            }
        }
        base.arrays.forEach { (name, items) ->
            val translated = translation.arrays[name] ?: return@forEach
            if (translated.size != items.size) {
                errors += "$locale/$fileName: $name has ${translated.size} items, and the base file has ${items.size}."
            }
        }

        val baseValues = base.labelledValues()
        translation.labelledValues().forEach { (label, value) ->
            val baseValue = baseValues[label] ?: pluralBaseValue(base, label) ?: return@forEach
            checkSpecifiers(locale, fileName, label, baseValue, value, errors, notes)
            if (androidResource) {
                if (StringResource.hasUnescapedApostrophe(value)) {
                    notes += "$fileName: $label has an apostrophe with no escape"
                }
            } else if (StringResource.hasBackslashQuote(value)) {
                errors += "$locale/$fileName: $label has a backslash before a quote. Compose shows the backslash. " +
                    "Use a plain apostrophe or quote."
            }
            val name = label.substringBefore('[')
            val untranslated = value == baseValue && StringResource.isTranslatable(baseValue)
            val allowed = name in allowList || "$locale:$name" in allowList
            if (untranslated && !allowed) notes += "$fileName: $label is still English"
        }
    }

    /**
     * Gives the base value of a plural item of a translation. A language can use a quantity that
     * English does not use (for example `many`). The `other` item of the base is then the base
     * value.
     */
    private fun pluralBaseValue(base: StringResource.Parsed, label: String): String? {
        val name = label.substringBefore('[')
        val items = base.plurals[name] ?: return null
        return items["other"]
    }

    /**
     * Compares the format specifiers of one value with the base value.
     *
     * A translation can put the arguments in a different order, and it can use an argument two
     * times. Thus the counts and the order are not important. The translation must ask only for
     * arguments that the base value gives, with the same conversion character. An argument that
     * the translation does not use causes no problem at run time, so it is only a warning.
     */
    private fun checkSpecifiers(
        locale: String,
        fileName: String,
        label: String,
        baseValue: String,
        value: String,
        errors: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val baseSpecifiers = StringResource.formatSpecifiers(baseValue)
        val specifiers = StringResource.formatSpecifiers(value)
        val baseByIndex = baseSpecifiers
            .mapNotNull { specifier -> specifier.index?.let { index -> index to specifier } }
            .toMap()

        specifiers.filter { specifier -> specifier.index != null }.forEach { specifier ->
            val baseSpecifier = baseByIndex[specifier.index]
            when {
                baseSpecifier == null ->
                    errors += "$locale/$fileName: $label asks for $specifier, which the base locale does not give."
                baseSpecifier.conversion != specifier.conversion ->
                    errors += "$locale/$fileName: $label uses $specifier where the base locale uses $baseSpecifier."
            }
        }

        // A specifier with no argument position takes the arguments in sequence, so the counts must agree.
        val baseUnpositioned = baseSpecifiers.count { specifier -> specifier.index == null }
        val unpositioned = specifiers.count { specifier -> specifier.index == null }
        if (baseUnpositioned != unpositioned) {
            errors += "$locale/$fileName: $label has $unpositioned format specifier(s) with no argument " +
                "position, and the base locale has $baseUnpositioned."
        }

        val used = specifiers.mapNotNull { specifier -> specifier.index }.toSet()
        (baseByIndex.keys - used).sorted().forEach { index ->
            notes += "$fileName: $label does not use the argument ${baseByIndex.getValue(index)}"
        }
    }
}
