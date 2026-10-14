import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.superdro.app.android"
    compileSdk = AndroidSdk.COMPILE
    // The native libraries of platform:android:camera (libusb, libuvc) need the same NDK.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "org.superdro"
        minSdk = AndroidSdk.MIN
        targetSdk = AndroidSdk.TARGET
        // From the project version (composite, resolveProjectVersion()).
        versionCode = projectVersionCode(project.version.toString())
        versionName = project.version.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // The upload key (docs/versioning-and-release-plan.md, section 7). The release `signingConfig`
    // exists only when the release workflow decrypted the keystore and set ANDROID_KEYSTORE_FILE.
    // A local build, a pull request build, and a validation build make an unsigned release APK.
    val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            // R8 removes the resources that no code and no manifest entry refers to. The Compose
            // resources are assets, so this step does not change them.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // With no keystore, the release build stays unsigned.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // The baselineProfile plugin makes the build types nonMinifiedRelease and
        // benchmarkRelease from release, with the same R8 rules. Thus this file declares no
        // benchmark build type of its own.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // The JVM unit tests use only the parts with no Android type.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        htmlReport = true
        // The full lint runs on the debug variant through the `lint` task. The lint vital
        // pass on each release variant examines the same sources again, so it is off.
        checkReleaseBuilds = false
    }

    sourceSets {
        // The manifest label reads @string/app_name. The copyStringRes task copies it from
        // core:resources (see below).
        getByName("main").res.directories.add(
            project.layout.buildDirectory.dir("copiedRes").get().asFile.path
        )
        // The debug adapter is bound for each build type: src/debug imports the module of
        // core:fake-adapter, src/release binds NoDebugAdapter. The profile build types use
        // src/profile (see profileBuildTypes below).
    }
}

kotlin {
    configureOptIns(material3 = true)
}

baselineProfile {
    mergeIntoMain = true
    // The generator removes the rules of the fake adapter (see below) itself. The filter of this
    // plugin does not apply to the startup profile.
}

// The build types that the baselineProfile plugin makes from release. The profile generator
// starts the app with the demo extra, and the live readout needs the fake adapter for that. Thus
// these two build types bind the fake adapter (src/profile) in place of NoDebugAdapter
// (src/release). The plugin copies the release source directories into these build types in its
// own finalizeDsl callback, which runs before this one. If the order changes, src/release and
// src/profile both declare debugModule, and the compiler stops the build. The release build type
// does not change: it has no fake adapter and no new code.
val profileBuildTypes = setOf("nonMinifiedRelease", "benchmarkRelease")

androidComponents {
    finalizeDsl { extension ->
        profileBuildTypes.forEach { name ->
            extension.sourceSets.getByName(name).kotlin.directories.apply {
                remove("src/release/kotlin")
                add("src/profile/kotlin")
            }
        }
    }
}

// The plugin makes the configurations of the profile build types after this script runs, so the
// dependencies block cannot name them.
configurations.matching { configuration ->
    configuration.name in profileBuildTypes.map { name -> "${name}Implementation" }
}.configureEach {
    dependencies.add(project.dependencies.create(projects.core.fakeAdapter))
}

dependencies {
    implementation(projects.core.connection)
    implementation(projects.core.debugAdapter)
    implementation(projects.core.di)
    implementation(projects.core.model)
    implementation(projects.core.resources)
    implementation(projects.core.store)
    implementation(projects.core.ui)
    implementation(projects.core.util)
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.camera)
    implementation(projects.platform.android.di)
    implementation(projects.platform.android.network)
    implementation(projects.platform.android.service)
    implementation(projects.platform.android.store)
    implementation(projects.platform.android.usb)

    // The fake adapter gets into the debug APK only. A release build has no access to it at
    // compile time, so the rule does not depend on R8.
    debugImplementation(projects.core.fakeAdapter)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.arbor.android)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kodein)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The device tests on an emulator.
    androidTestImplementation(projects.core.fakeAdapter)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.testJunit)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    baselineProfile(projects.app.android.baseline)
}

// Only the manifest label reads a string through Android resources. The code reads each string
// through Compose Resources, which selects the locale itself. Thus only the base file of the app
// frame is copied, not the values-* directories.
val copyStringRes = tasks.register<Copy>("copyStringRes") {
    from(rootProject.layout.projectDirectory.dir("core/resources/src/commonMain/composeResources/values")) {
        include("strings_app.xml")
    }
    into(layout.buildDirectory.dir("copiedRes/values"))
}

tasks.named("preBuild") {
    dependsOn(copyStringRes)
}
