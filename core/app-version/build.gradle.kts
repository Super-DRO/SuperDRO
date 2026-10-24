import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets
import org.superdro.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// The version gets into common code only through generated source. `rootProject.version` is a
// Gradle value, and commonMain must stay free of platform code (no BuildConfig). Only the root
// project has the version (see the root build.gradle.kts).
//
// The stamp leaves out the build metadata after `+` (commits, sha, dirty). That part changes at
// each commit, and a value that changes at each commit makes each dependent module compile
// again. A release stays exact (`26.41.1`), and a development build stays `26.41.1-dev`. The full
// text stays in the Android versionName. Use -Psuperdro.stampBuildMetadata=true to put the
// full text into this stamp too.
val stampBuildMetadata = providers.gradleProperty("superdro.stampBuildMetadata")
    .map(String::toBoolean)
    .getOrElse(false)

val generateAppVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/appVersion")
    val fullVersion = rootProject.version.toString()
    val versionName = if (stampBuildMetadata) fullVersion else fullVersion.substringBefore('+')
    val versionCode = projectVersionCode(fullVersion)
    outputs.dir(outputDir)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    doLast {
        // The text goes into Kotlin source. Escape the backslash, the quote, and `$`, or the
        // file does not compile.
        val literal = versionName
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\\$")
        val file = outputDir.get()
            .file("org/superdro/core/appversion/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package org.superdro.core.appversion

            /**
             * The version stamp of the build. GENERATED: do not edit.
             *
             * [NAME] is clean on a release (`26.41.1`) and has the `-dev` marker on each other
             * build. [CODE] is the integer version code. The build metadata after `+` is not here,
             * so that the value stays the same between commits.
             *
             * These values are not `const`, on purpose. A `const val` goes into each call site and
             * is a part of the ABI of this module. Then each dependent module compiles again when
             * the value changes. Do not add `const`.
             */
            object AppVersion {
                val NAME: String = "$literal"
                val CODE: Int = $versionCode
            }

            """.trimIndent()
        )
    }
}

kotlin {
    configureOptIns(serialization = false, coroutines = false)
    superDroTargets("org.superdro.core.appversion")
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAppVersion)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
