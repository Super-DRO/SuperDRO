import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.androidTest)
}

// The Baseline Profile generator of app:android. It runs only on a
// device: `./gradlew :app:android:generateBaselineProfile`.
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

    buildTypes {
        create("benchmark") {
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app:android"
}

kotlin {
    configureOptIns(serialization = false)
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.testExt.junit)
}
