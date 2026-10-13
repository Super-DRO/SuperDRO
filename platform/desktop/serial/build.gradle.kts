import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The SPP and USB serial transport module of the desktop app. The
// operating system maps a paired SPP adapter and a USB serial adapter to a serial port. jSerialComm
// opens these ports. A transport module depends only on core:connection and its link library.
kotlin {
    configureOptIns()
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                implementation(projects.core.connection)

                implementation(libs.arbor)
                implementation(libs.jserialcomm)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// The virtual time functions of kotlinx-coroutines-test (advanceTimeBy, runCurrent) are
// experimental. Only the tests use them.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name == "compileTestKotlinJvm") {
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}
