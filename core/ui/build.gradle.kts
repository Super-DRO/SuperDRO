import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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

// The render tests write the PNG files here. An agent reads them after `:core:ui:jvmTest`.
tasks.named<Test>("jvmTest") {
    val screenshotDir = layout.buildDirectory.dir("screenshots")
    outputs.dir(screenshotDir).withPropertyName("screenshots")
    systemProperty("superdro.screenshotDir", screenshotDir.get().asFile.absolutePath)
    // The render needs no display.
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
