import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The SPP transport module: it depends only on core:connection
// and the Android Bluetooth API. core:model gives AdapterAddress.

android {
    namespace = "org.superdro.platform.android.bluetooth"
    compileSdk = AndroidSdk.COMPILE
    defaultConfig {
        minSdk = AndroidSdk.MIN
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.connection)
    implementation(projects.core.model)

    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.kotlinx.coroutines.core)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
