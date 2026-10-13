package org.superdro.composite

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `buildLogicVersion()` has two copies: one in `composite/build.gradle.kts`, which gives the
 * published jar its version, and one in the root `settings.gradle.kts`, which asks for that
 * version. The two builds cannot share a file, so this test compares the two copies. Only the
 * indentation and the `java.security.` prefix can be different: in a project script, `java`
 * is the Java plugin extension, so the composite copy imports `MessageDigest`.
 */
class BuildLogicVersionParityTest {

    @Test
    fun `the two copies of buildLogicVersion are identical`() {
        // Gradle runs the tests with the composite project directory as the working directory.
        val compositeCopy = extractFunction(File("build.gradle.kts"))
        val settingsCopy = extractFunction(File("../settings.gradle.kts"))
        assertEquals(compositeCopy, settingsCopy)
    }

    private fun extractFunction(file: File): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { line -> line.trim() == SIGNATURE }
        check(start >= 0) { "No `$SIGNATURE` line in ${file.path}" }
        val indent = lines[start].takeWhile { char -> char == ' ' }
        val end = (start + 1 until lines.size).firstOrNull { index -> lines[index] == "$indent}" }
        checkNotNull(end) { "No closing brace for buildLogicVersion() in ${file.path}" }
        return lines.subList(start, end + 1)
            .joinToString("\n") { line -> line.removePrefix(indent) }
            .replace("java.security.MessageDigest", "MessageDigest")
    }

    private companion object {
        const val SIGNATURE = "fun buildLogicVersion(root: File): String {"
    }
}
