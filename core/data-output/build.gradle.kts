import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// The data output. Common code has the formats, the cadence, the TCP server, and the MQTT
// topics. The source set jvmAndAndroidMain has the parts that need a JVM library: the TLS 1.3 PSK
// mode through BouncyCastle and the MQTT client
// through Eclipse Paho.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.dataoutput")
    android {
        // R8 must keep the Paho classes that Paho loads through reflection and ServiceLoader.
        // Without the rule, the client fails at each start.
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }
    // The source set jvmAndAndroidMain has the code for the two JVM targets. The default
    // hierarchy template gets one more group, so that no explicit dependsOn() edge is necessary.
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
                // The public API has the configuration, the contracts, and the coroutine types.
                api(projects.core.connection)
                api(projects.core.model)
                api(projects.core.util)
                implementation(projects.core.readout)

                api(libs.kotlinx.coroutines.core)
                implementation(libs.arbor)
                implementation(libs.ktor.network)
            }
        }
        val jvmAndAndroidMain by getting {
            dependencies {
                implementation(libs.bouncycastle.tls)
                implementation(libs.paho.mqtt)
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
                implementation(libs.bouncycastle.tls)
                implementation(libs.paho.mqtt)
            }
        }
    }
}

dependencies {
    // The broker of the MQTT tests. The tests turn off its telemetry. The error reports (Bugsnag,
    // with a version range), the metrics, and the SQL parts are not necessary for a broker in memory.
    "jvmTestImplementation"(libs.moquette.broker) {
        exclude(group = "com.bugsnag")
        exclude(group = "com.librato.metrics")
        exclude(group = "io.dropwizard.metrics")
        exclude(group = "com.zaxxer")
    }
}
