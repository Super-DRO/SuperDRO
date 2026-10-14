import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.androidTest)
}

// The Baseline Profile generator of app:android. It runs on the Gradle Managed Device below:
// `./gradlew :app:android:generateBaselineProfile`. The output goes to
// app/android/src/main/generated/baselineProfiles.
val profileDevice = "pixelTabletApi33"

android {
    namespace = "org.superdro.app.android.baseline"
    compileSdk = AndroidSdk.COMPILE

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = AndroidSdk.MIN
        targetSdk = AndroidSdk.TARGET

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The baselineProfile plugin makes the build types nonMinifiedRelease and benchmarkRelease.
    // They agree with the build types of the same names in app:android.

    // A Gradle Managed Device: Gradle starts and stops its own emulator. The generator thus never
    // runs on an attached tablet or on an emulator of a different user. The image is the
    // tablet layout with the Google APIs, API 33, x86_64.
    testOptions {
        managedDevices {
            localDevices {
                create(profileDevice) {
                    device = "Pixel Tablet"
                    sdkVersion = 33
                    systemImageSource = "google"
                    testedAbi = "x86_64"
                }
            }
        }
    }

    targetProjectPath = ":app:android"
}

kotlin {
    configureOptIns(serialization = false)
}

baselineProfile {
    managedDevices += profileDevice
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.benchmark)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.testExt.junit)
}
