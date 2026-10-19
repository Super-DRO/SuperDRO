import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The desktop Kodein bindings (desktopPlatformModule): the transport factories, the capability
// source, the keep-alive, the SQL driver, and the other platform contracts. app:desktop
// imports the module with coreDiModule.
kotlin {
    configureOptIns()
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                implementation(projects.core.camera)
                implementation(projects.core.connection)
                implementation(projects.core.connectionTcp)
                implementation(projects.core.debugAdapter)
                // Only for the DI tag of the link dispatcher (LINK_IO_DISPATCHER).
                implementation(projects.core.di)
                implementation(projects.core.model)
                implementation(projects.core.store)
                implementation(projects.core.util)
                implementation(projects.platform.desktop.serial)
                implementation(projects.platform.desktop.store)

                implementation(libs.arbor)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
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
