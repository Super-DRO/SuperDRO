package org.superdro.composite.util

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Gives the calendar version of the app. All modules use this one value. The repository has no
 * `VERSION` file: the value comes from `git describe`.
 *
 * The form is `<2-digit ISO week-year>.<ISO week>.<count>`:
 *
 * - A build on a tag with a clean tree: `v26.41.1` gives `26.41.1` (no marker).
 * - Each other build: `v26.41.1-7-gabc123` gives `26.41.1-dev+7-gabc123`. A tree with no tag
 *   gives `0.0.0-dev+g<sha>`.
 *
 * The `-dev` marker never shows on a release. Thus a release build and a development build are
 * always different.
 */
fun Project.resolveProjectVersion(): String =
    providers.of(GitVersionValueSource::class.java) {
        // Run git in the root of the repository, so that each project gets the same value.
        parameters.workingDir.set(rootDir.absolutePath)
    }.get()

/**
 * Gives the integer version code from the first part `year.week.count` of [version]:
 *
 *   code = (year % 100) * 100000 + week * 1000 + count
 *
 * `26.41.1` gives `2641001`. The function ignores the `-dev+...` part. A version with no tag
 * (`0.0.0-dev`) gives `1`, because Android needs `versionCode >= 1`.
 *
 * `version_code()` in `scripts/cut_version.py` uses the same formula. Keep the two the same.
 */
fun projectVersionCode(version: String): Int {
    val base = version.substringBefore('-')
    val parts = base.split('.')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val week = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val code = (year % 100) * 100000 + week * 1000 + count
    return code.coerceAtLeast(1)
}

/**
 * Gives the `major.minor.patch` part of [version] with no `-dev+<metadata>` part. A desktop
 * installer tool accepts only this form. The MSI tool also refuses a major part above 255. Thus
 * the year has two digits.
 */
fun packagingVersion(version: String): String = version.substringBefore('-')

/**
 * The `git describe --match` glob of a release tag. `scripts/cut_version.py` makes only tags of
 * this form.
 */
internal const val RELEASE_TAG_GLOB = "v[0-9]*.[0-9]*.[0-9]*"

/**
 * Changes the text of `git describe --tags --always --dirty` into the version. The function has
 * no side effects, so that a unit test can examine it.
 */
internal fun parseGitDescribe(raw: String?): String {
    val describe = raw?.trim().orEmpty()
    if (describe.isEmpty()) {
        // No git, or a failure: give a valid development version.
        return "0.0.0-dev"
    }
    val d = describe.removePrefix("v")

    // Exactly on a tag with a clean tree: the release version.
    val releaseRegex = Regex("""^\d+\.\d+\.\d+$""")
    if (releaseRegex.matches(d)) return d

    // `<year.week.count>-<extra>` (commits after the tag, or a dirty tree): a development build.
    val taggedRegex = Regex("""^(\d+\.\d+\.\d+)(?:-(.+))?$""")
    val match = taggedRegex.matchEntire(d)
    if (match != null) {
        val baseVersion = match.groupValues[1]
        val extra = match.groupValues[2]
        // SemVer build metadata permits no `+`, so change each `+` to `.`.
        return if (extra.isEmpty()) "$baseVersion-dev" else "$baseVersion-dev+${extra.replace('+', '.')}"
    }

    // No tag: `git describe --always` gave a short SHA (with `-dirty` when the tree is dirty).
    return "0.0.0-dev+g$d"
}

/**
 * Runs `git describe` in a form that the configuration cache accepts. Gradle runs a
 * [ValueSource] again at the start of each build and compares the result. Thus the version
 * stays correct, and a configuration-time process does not make the cache entry invalid.
 */
abstract class GitVersionValueSource : ValueSource<String, GitVersionValueSource.Params> {

    interface Params : ValueSourceParameters {
        val workingDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val stdout = ByteArrayOutputStream()
        val raw = runCatching {
            execOperations.exec {
                workingDir = java.io.File(parameters.workingDir.get())
                // Only a release tag `v<year>.<week>.<count>` counts. A different tag on the
                // branch (for example a backup tag) does not change the version.
                commandLine("git", "describe", "--tags", "--match", RELEASE_TAG_GLOB, "--always", "--dirty")
                standardOutput = stdout
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
            stdout.toString().trim()
        }.getOrNull()
        return parseGitDescribe(raw)
    }
}
