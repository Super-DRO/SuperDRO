import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// No dependency on coroutines or Compose.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.readout")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.model)
                implementation(projects.core.util)

                // The decimal numbers of the keypad expression evaluator.
                implementation(libs.bignum)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
