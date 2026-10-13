import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinJvm)
    // The UI validation (package ui) runs Compose UI tests on the JVM.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The acceptance tests of SuperDRO. The module has only tests. The tests check the
// requirements, the defects, and the rules of all modules.
kotlin {
    configureOptIns(serialization = false)
}

dependencies {
    testImplementation(projects.core.model)
    testImplementation(projects.core.util)
    // The validation of the readout, the functions and the geometry, and the store, the files, the data output,
    // and the camera (packages readout, functions, and store).
    testImplementation(projects.core.readout)
    testImplementation(projects.core.functions)
    testImplementation(projects.core.store)
    testImplementation(projects.core.drawing)
    testImplementation(projects.core.camera)
    testImplementation(projects.core.dataOutput)
    testImplementation(projects.core.connection)
    testImplementation(libs.sqldelight.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.bouncycastle.tls)
    testImplementation(projects.core.protocol)
    testImplementation(projects.core.connectionTcp)
    testImplementation(projects.core.connectionBle)
    testImplementation(projects.core.fakeAdapter)
    // The validation of the UI requirements (package ui): App() and the screens of core:ui with the
    // full DI graph, fake sessions, and the fake adapter.
    testImplementation(projects.core.ui)
    testImplementation(projects.core.di)
    testImplementation(projects.core.debugAdapter)
    testImplementation(projects.core.resources)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.runtime)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.compose.material3)
    testImplementation(libs.compose.components.resources)
    testImplementation(libs.androidx.lifecycle.runtimeCompose)
    testImplementation(libs.kodein)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

tasks.test {
    val root = rootDir
    // The tests read the source tree. Declare it as an input, so that the build cache does not keep
    // an old result. The store validation reads the version catalog and the R8 rules.
    inputs.file(root.resolve("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree(root) {
            include("core/**/src/**/*.kt", "platform/**/src/**/*.kt", "app/**/src/**/*.kt", "validation/src/**/*.kt")
            exclude("**/build/**")
        }
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sources")
    // The readout validation reads the English string files of core:resources.
    inputs.files(
        fileTree(root) { include("core/resources/src/commonMain/composeResources/values*/*.xml") }
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("readoutStrings")
    systemProperty("superdro.rootDir", root.absolutePath)
    testLogging { showStandardStreams = true }
    // The UI validation draws with no display and writes its PNG files here.
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    val uiScreenshotDir = layout.buildDirectory.dir("ui-screenshots")
    outputs.dir(uiScreenshotDir).withPropertyName("uiScreenshots")
    systemProperty("superdro.uiScreenshotDir", uiScreenshotDir.get().asFile.absolutePath)
    // The UI validation reads the string files of all languages and the theme sources.
    inputs.files(
        fileTree(root) { include("core/resources/src/commonMain/composeResources/**") }
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uiResources")
}
