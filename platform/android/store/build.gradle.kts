import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// StoreIOProviderAndroid, the SQL driver with the bundled SQLite, and the Android
// Keystore SecretStore.

android {
    namespace = "org.superdro.platform.android.store"
    compileSdk = AndroidSdk.COMPILE
    defaultConfig {
        minSdk = AndroidSdk.MIN
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.store)
    implementation(projects.core.util)

    implementation(libs.arbor)
    // The version check of a debuggable app runs on Dispatchers.IO.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io)
    implementation(libs.sqldelight.android)
    // The bundled SQLite 3.53.4. It needs compileSdk 37.
    implementation(libs.sqlite.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.testJunit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
