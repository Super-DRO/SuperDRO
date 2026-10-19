import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// No dependency on coroutines or Compose.
// The serialization plugin gives the session types of the error map wizard and of the
// tip calibration. kotlinx-serialization comes through core:model.
kotlin {
    configureOptIns(coroutines = false)
    superDroTargets("org.superdro.core.functions")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.readout)
                // The public API uses the model types (Workspace, SubDatum, Guide, Drawing).
                api(projects.core.model)
                implementation(projects.core.util)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
