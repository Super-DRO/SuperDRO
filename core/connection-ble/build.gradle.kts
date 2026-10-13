import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// A transport module depends only on core:connection and its link library. This is the only
// module with BlueFalcon.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.connection.ble")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.connection)

                // api: BleTransportFactory and BleAdapterDiscovery take a BlueFalcon client.
                api(libs.blue.falcon)
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
