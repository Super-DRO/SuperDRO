import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.connection")
    // The source set jvmAndAndroidMain has the code for the two JVM targets: the file recorder of
    // the byte streams, which uses java.io.File. The default hierarchy template gets
    // one more group, so that no explicit dependsOn() edge is necessary.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withCompilations { compilation -> compilation.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The contracts carry AdapterAddress, TransportKind, and the coroutine types, so
                // each consumer sees them: api, not implementation.
                api(projects.core.model)
                api(projects.core.protocol)
                api(projects.core.readout)
                // The public constructor of ConnectionManager has the session Clock.
                api(projects.core.util)

                api(libs.kotlinx.coroutines.core)
                implementation(libs.arbor)
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
