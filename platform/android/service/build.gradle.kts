import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The connection service: ConnectionService, the keep-alive, the
// start at boot, the link dispatcher, the wake lock, and the battery
// optimization state.

android {
    namespace = "org.superdro.platform.android.service"
    compileSdk = AndroidSdk.COMPILE
    defaultConfig {
        minSdk = AndroidSdk.MIN
    }
}

kotlin {
    configureOptIns()
}

// The virtual time functions of kotlinx-coroutines-test are experimental. Only the unit tests use them.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) {
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(projects.core.connection)
    implementation(projects.core.model)
    // The texts of the notification, read with getString() of Compose Resources.
    implementation(projects.core.resources)

    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.kodein)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
