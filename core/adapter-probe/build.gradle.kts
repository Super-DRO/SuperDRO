import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// The logic of the adapter probe app: the test definitions, the
// link control, the log writer of format 1, the statistics, the summary lines, and the report.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.adapterprobe")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.connection)
                implementation(projects.core.protocol)

                implementation(libs.arbor)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                // The tests run each test definition against the fake adapter.
                implementation(projects.core.fakeAdapter)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
