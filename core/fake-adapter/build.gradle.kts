import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Debug builds and tests only. app:android and app:adapter-probe use debugImplementation.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.fakeadapter")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.debugAdapter)
                implementation(projects.core.model)
                implementation(projects.core.protocol)

                implementation(libs.arbor)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                // The fake adapter behind a local TCP server.
                implementation(libs.ktor.network)
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
                // The TCP server scenario with the real TcpTransport.
                implementation(projects.core.connectionTcp)
            }
        }
    }
}
