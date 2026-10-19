import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// No dependency on coroutines or Compose.
kotlin {
    configureOptIns(coroutines = false)
    superDroTargets("org.superdro.core.drawing")
    sourceSets {
        val commonMain by getting {
            dependencies {
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
