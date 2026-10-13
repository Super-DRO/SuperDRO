package org.superdro.validation

import java.io.File

/** The location of the source tree for the acceptance tests. */
object Traceability {

    /** The root of the repository. The build file of this module gives the system property. */
    val rootDir: File = File(System.getProperty("superdro.rootDir") ?: "..").canonicalFile

    /** Tells if [file] is a test source: a source set whose name ends with "Test" or "test", or `validation`. */
    fun isTestSource(file: File): Boolean {
        val path = file.relativeTo(rootDir).invariantSeparatorsPath
        if (path.startsWith("validation/")) return true
        val sourceSet = path.substringAfter("/src/", "").substringBefore('/')
        return sourceSet.endsWith("Test") || sourceSet == "test"
    }
}
