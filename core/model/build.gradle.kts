import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    configureOptIns(coroutines = false)
    superDroTargets("org.superdro.core.model")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.util)

                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
