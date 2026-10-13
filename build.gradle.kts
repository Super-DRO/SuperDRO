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
