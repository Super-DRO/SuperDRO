package org.superdro.composite.task

import org.superdro.composite.util.LocalizationComparison
import org.superdro.composite.util.StringResource
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Compares each string file of the base locale (`values/strings_*.xml`) with the file of the
 * same name in each translation directory (`values-es`, `values-fr`, `values-de`,
 * `values-pt-rBR`, `values-nl`).
 *
 * [LocalizationComparison] gives the rules. In short: a file or a name that a locale does not
 * have fails the build, and so does a format specifier that does not agree. A value that is
 * still English is only a warning.
 *
 * The task writes each result to [reportFile]. The report also makes the task cacheable. Gradle
 * does not run the task again when the inputs did not change, so an empty console does not prove
 * a clean result. Read [reportFile] for that.
 */
@CacheableTask
abstract class LocalizationCheckTask : DefaultTask() {

    /**
     * The string files of all locales: `values/strings_*.xml` and `values-<qualifier>/strings_*.xml`.
     * The parent directory of a file gives its locale.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringFiles: ConfigurableFileCollection

    /** The directory name of the base locale: `values`. */
    @get:Input
    abstract val baseLocale: Property<String>

    /**
     * The directory name of each locale that the app ships, for example `values-de`. A locale
     * with no directory gives no file. Only this list can report it.
     */
    @get:Input
    abstract val expectedLocales: SetProperty<String>

    /**
     * The names of the strings that stay in English in each locale, for example the app name.
     * The task does not report these names as untranslated.
     *
     * An entry is a bare name, which covers each locale, or `<locale>:<name>`, which covers one
     * locale. Use the second form when possible: a word can be the same in one language and
     * different in a different language.
     */
    @get:Input
    abstract val untranslatedAllowList: SetProperty<String>

    /**
     * The names of the string files that the apps copy into the Android resources, for example
     * `strings_app.xml`. AAPT2 reads these files, so each apostrophe needs a backslash. Only Compose
     * reads the other files, so they must use a plain apostrophe.
     */
    @get:Input
    abstract val androidResourceFiles: SetProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val baseName = baseLocale.get()
        val byLocale = stringFiles.files
            .groupBy { file -> file.parentFile.name }
            .mapValues { (_, files) -> files.associate { file -> file.name to StringResource.read(file) } }
        val base = byLocale[baseName].orEmpty()
        if (base.isEmpty()) {
            throw GradleException("The localization check found no string file in $baseName.")
        }
        val translations = byLocale.filterKeys { locale -> locale != baseName }
        val result = LocalizationComparison.compare(
            baseLocale = baseName,
            base = base,
            translations = translations,
            expectedLocales = expectedLocales.get(),
            allowList = untranslatedAllowList.get(),
            androidResourceFiles = androidResourceFiles.getOrElse(emptySet()),
        )

        val report = writeReport(result, base.keys, translations)
        // The console gets one line for each locale with warnings. The report holds the details.
        result.warnings.forEach { (locale, notes) ->
            logger.warn("Localization: {} has {} warning(s). See {}.", locale, notes.size, report)
        }
        if (result.errors.isNotEmpty()) {
            throw GradleException(
                result.errors.joinToString(
                    prefix = "The localization check found ${result.errors.size} error(s):\n  ",
                    separator = "\n  "
                )
            )
        }
    }

    private fun writeReport(
        result: LocalizationComparison.Result,
        baseFiles: Set<String>,
        translations: Map<String, Map<String, StringResource.Parsed>>,
    ): File {
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("Base files: ${baseFiles.sorted().joinToString()}")
                appendLine("Locales: ${translations.keys.sorted().joinToString()}")
                appendLine("Errors: ${result.errors.size}")
                result.errors.forEach { error -> appendLine("  $error") }
                result.warnings.forEach { (locale, notes) ->
                    appendLine()
                    appendLine("$locale: ${notes.size} warning(s)")
                    notes.forEach { note -> appendLine("  $note") }
                }
            }
        )
        return report
    }
}
