import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    // Only for the JVM test of App() with the full graph: the test code has composable lambdas.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// core:di depends on core:ui, and core:ui does not depend on core:di.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.di")
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The camera UI module binds the camera presenters with the UvcCameraBackend.
                implementation(projects.core.camera)
                implementation(projects.core.connection)
                // The one DataOutputPlatform and the runner of the outputs (DataOutputDiModule.kt).
                implementation(projects.core.dataOutput)
                implementation(projects.core.debugAdapter)
                implementation(projects.core.model)
                implementation(projects.core.readout)
                implementation(projects.core.store)
                implementation(projects.core.ui)
                implementation(projects.core.util)

                implementation(libs.arbor)
                // The Compose compiler of the tests needs the runtime in each compilation.
                implementation(libs.compose.runtime)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.runtime)
            }
        }
        // The no-op platform module of the JVM tests binds the fake camera backend.
        val jvmMain by getting {
            dependencies {
                implementation(projects.core.camera)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.sqldelight.jdbc)
                implementation(libs.sqlite.jdbc)
                // The PSK client of the data output test with the full graph.
                implementation(libs.bouncycastle.tls)
                // App() with the real connection manager and the fake adapter.
                implementation(projects.core.fakeAdapter)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// The UI test draws with no display.
tasks.named<Test>("jvmTest") {
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
