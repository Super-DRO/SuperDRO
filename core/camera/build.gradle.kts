import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.camera")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.model)

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

// The virtual time functions of kotlinx-coroutines-test (advanceTimeBy, runCurrent, currentTime)
// are experimental. Only the tests use them.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name == "compileTestKotlinJvm") {
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
