import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The network state of the TCP links and the interface list of the data output. The
// contract NetworkInterfaces is in core:connection, so this module does not depend on
// core:data-output, and the adapter probe app does not get it.

android {
    namespace = "org.superdro.platform.android.network"
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

    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.kotlinx.coroutines.core)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
