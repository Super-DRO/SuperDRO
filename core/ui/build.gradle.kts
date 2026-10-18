import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

private class ScreenshotDirArgs(
    private val path: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dsuperdro.screenshotDir=$path")
}

// This module has no dependency on core:di, on a transport module, or on BlueFalcon. The UI sees
// the sessions only through the types of core:connection.
kotlin {
    configureOptIns(material3 = true)
    superDroTargets("org.superdro.core.ui")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.appVersion)
                implementation(projects.core.camera)
                implementation(projects.core.connection)
                implementation(projects.core.dataOutput)
                // The seam of the demo adapter: the add-device sheet shows "Demo" only when a
                // debug build binds a fake adapter. The module has no transport code.
                implementation(projects.core.debugAdapter)
                implementation(projects.core.drawing)
                implementation(projects.core.functions)
                implementation(projects.core.model)
                implementation(projects.core.readout)
                implementation(projects.core.resources)
                implementation(projects.core.store)
                implementation(projects.core.util)

                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.arbor)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material3.adaptive)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.androidx.navigationevent.compose)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // The render tests draw App() to PNG files with ImageComposeScene, so that an agent can
        // see the UI with no emulator (ScreenshotRenderer in jvmTest).
        val jvmTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // runComposeUiTest for the UI tests.
                implementation(libs.compose.ui.test)
                // The presenter tests use the real repositories on a database in memory.
                implementation(libs.sqldelight.jdbc)
                implementation(libs.sqlite.jdbc)
            }
        }
    }
}

val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")
val baseTestClasses = jvmTestCompilation.output.classesDirs
val baseRuntimeClasspath = files(
    jvmTestCompilation.output.allOutputs - jvmTestCompilation.output.classesDirs,
    jvmMainCompilation.output.allOutputs,
    jvmTestCompilation.runtimeDependencyFiles,
)
val parallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

fun registerUiDomainTest(
    taskName: String,
    testIncludes: List<String>,
    testExcludes: List<String> = emptyList(),
): org.gradle.api.tasks.TaskProvider<Test> {
    val syncClasses = tasks.register<Sync>("sync${taskName.replaceFirstChar(Char::uppercase)}Classes") {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        // Include all shared non-*Test support/fixture classes so cross-package harnesses resolve.
        from(baseTestClasses) {
            exclude("**/*Test.class", "**/*Test$*.class", "**/*TestKt.class", "**/*TestKt$*.class")
        }
        // Include only the *Test classes of this UI domain.
        from(baseTestClasses) {
            include(testIncludes)
            exclude(testExcludes)
        }
        into(layout.buildDirectory.dir("domain-test-classes/$taskName"))
    }
    return tasks.register<Test>(taskName) {
        testClassesDirs = files(syncClasses)
        classpath = files(syncClasses, baseRuntimeClasspath)
        include(testIncludes)
        exclude(testExcludes)
        maxParallelForks = parallelForks
        val screenshotDir = layout.buildDirectory.dir("screenshots/$taskName")
        outputs.dir(screenshotDir).withPropertyName("screenshots")
        jvmArgumentProviders.add(ScreenshotDirArgs(screenshotDir.get().asFile.absolutePath))
        systemProperty("java.awt.headless", "true")
        systemProperty("skiko.renderApi", "SOFTWARE")
    }
}

val screenshotTestPattern = "**/*ScreenshotTest*"

val specificDomainPatterns = listOf(
    "org/superdro/core/ui/adapter/**/*Test*",
    "org/superdro/core/ui/functions/**/*Test*",
    "org/superdro/core/ui/graphics/**/*Test*",
    "org/superdro/core/ui/readout/**/*Test*",
    "org/superdro/core/ui/settings/**/*Test*",
)

val jvmTestDomains = listOf(
    registerUiDomainTest("jvmTestAdapter", listOf("org/superdro/core/ui/adapter/**/*Test*"), listOf(screenshotTestPattern)),
    registerUiDomainTest("jvmTestFunctions", listOf("org/superdro/core/ui/functions/**/*Test*"), listOf(screenshotTestPattern)),
    registerUiDomainTest("jvmTestGraphics", listOf("org/superdro/core/ui/graphics/**/*Test*"), listOf(screenshotTestPattern)),
    registerUiDomainTest("jvmTestReadout", listOf("org/superdro/core/ui/readout/**/*Test*"), listOf(screenshotTestPattern)),
    registerUiDomainTest("jvmTestSettings", listOf("org/superdro/core/ui/settings/**/*Test*"), listOf(screenshotTestPattern)),
    registerUiDomainTest("jvmTestTabs", listOf("org/superdro/core/ui/**/*Test*"), specificDomainPatterns + screenshotTestPattern),
    registerUiDomainTest("jvmTestScreenshots", listOf(screenshotTestPattern)),
)

val hasCommandLineTestFilter = gradle.startParameter.taskRequests.any { "--tests" in it.args }

// The render tests write the PNG files here. An agent reads them after `:core:ui:jvmTest`.
tasks.named<Test>("jvmTest") {
    if (!hasCommandLineTestFilter) {
        dependsOn(jvmTestDomains)
        enabled = false
    }
    val screenshotDir = layout.buildDirectory.dir("screenshots/jvmTest")
    outputs.dir(screenshotDir).withPropertyName("screenshots")
    jvmArgumentProviders.add(ScreenshotDirArgs(screenshotDir.get().asFile.absolutePath))
    // The render needs no display.
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
