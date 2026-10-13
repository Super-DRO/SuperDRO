import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.debugadapter")
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The signatures of the seam carry the types of core:connection, so each
                // consumer sees them: api, not implementation.
                api(projects.core.connection)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
