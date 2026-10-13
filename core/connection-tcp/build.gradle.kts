import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// A transport module depends only on core:connection and its link library (Ktor sockets).
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.connection.tcp")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.connection)

                implementation(libs.ktor.network)
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
