import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The Android Kodein bindings (platformModule): the transport factories, the keep-alive, the SQL
// driver, and the other platform contracts.

android {
    namespace = "org.superdro.platform.android.di"
    compileSdk = AndroidSdk.COMPILE
    defaultConfig {
        minSdk = AndroidSdk.MIN
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.camera)
    implementation(projects.core.connection)
    implementation(projects.core.connectionBle)
    implementation(projects.core.connectionTcp)
    implementation(projects.core.debugAdapter)
    implementation(projects.core.model)
    implementation(projects.core.store)
    implementation(projects.core.util)
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.camera)
    implementation(projects.platform.android.network)
    implementation(projects.platform.android.service)
    implementation(projects.platform.android.store)
    implementation(projects.platform.android.usb)

    implementation(libs.arbor)
    implementation(libs.blue.falcon)
    implementation(libs.blue.falcon.engine.android)
    implementation(libs.kodein)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.runtime)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
