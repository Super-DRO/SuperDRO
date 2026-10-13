import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The USB serial transport module: it depends only on
// core:connection and its link library, usb-serial-for-android. core:model gives AdapterAddress.

android {
    namespace = "org.superdro.platform.android.usb"
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
    implementation(libs.usb.serial)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
