import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.superdro.composite.util.resolveProjectVersion

plugins {
    // Declare the plugins here with `apply false`, so that each subproject does not load
    // them again in its own class loader.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidBaseline) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.composite) apply false
}

// One calendar version for all modules, from `git describe` (composite, resolveProjectVersion()).
// A build on a tag gets a clean `26.41.1`. Each other build gets a `-dev` marker.
val projectVersion = resolveProjectVersion()
allprojects {
    version = projectVersion
}

// The Compose compiler options of each module with the Compose compiler plugin. No convention
// plugin applies Compose, so the root script sets them in one place.
// - compose-stability.conf: the immutable types of the other modules and of the libraries.
// - `-PcomposeReports=true` writes the compiler reports and metrics to
//   build/compose_compiler of each module. A normal build writes none. The build cache can
//   skip a compile task, so add `--rerun` to the compile task, for example
//   `./gradlew -PcomposeReports=true :core:ui:compileKotlinJvm --rerun`.
val composeStabilityFile = layout.projectDirectory.file("compose-stability.conf")
val composeReports = providers.gradleProperty("composeReports").map { value -> value.toBoolean() }.getOrElse(false)
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(composeStabilityFile)
            if (composeReports) {
                reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
            }
        }
    }
}
