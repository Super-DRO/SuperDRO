import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The adapter probe app. It does not depend on core:ui, core:store, or core:data-output.
android {
    namespace = "org.superdro.app.adapterprobe"
    compileSdk = AndroidSdk.COMPILE

    defaultConfig {
        // A different application ID, so that the app can stay on the tablet beside SuperDRO.
        applicationId = "org.superdro.adapterprobe"
        minSdk = AndroidSdk.MIN
        targetSdk = AndroidSdk.TARGET
        versionCode = projectVersionCode(project.version.toString())
        versionName = project.version.toString()
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets {
        // The manifest label reads @string/probe_app_name. copyStringRes copies it from core:resources.
        getByName("main").res.directories.add(
            project.layout.buildDirectory.dir("copiedRes").get().asFile.path
        )
    }

    // The same upload key as app:android (docs/versioning-and-release-plan.md, section 7). The
    // `signingConfig` exists only when the release workflow set ANDROID_KEYSTORE_FILE.
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // With no keystore, the release build stays unsigned.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    configureOptIns(material3 = true)
}

dependencies {
    implementation(projects.core.adapterProbe)
    implementation(projects.core.connection)
    implementation(projects.core.connectionTcp)
    implementation(projects.core.protocol)
    implementation(projects.core.resources)
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.network)
    implementation(projects.platform.android.usb)

    // The debug menu connects to the fake adapter. Only the debug build has the module
    // (src/debug); src/release has no fake adapter.
    debugImplementation(projects.core.fakeAdapter)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.arbor.android)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    // JVM unit tests of the parts with no Android type (no Robolectric).
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

// The release class path has no core:ui, core:store, and no core:data-output. `./gradlew :app:adapter-probe:checkReleaseClasspath` fails when one of them is there.
val checkReleaseClasspath = tasks.register("checkReleaseClasspath") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when the release runtime class path has core:ui, core:store, or core:data-output."
    val forbidden = setOf(":core:ui", ":core:store", ":core:data-output")
    val paths = configurations.named("releaseRuntimeClasspath").map { configuration ->
        configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
            (component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
        }.toSortedSet()
    }
    inputs.property("projects", paths.map { it.joinToString(",") })
    doLast {
        val found = paths.get().filter { it in forbidden }
        if (found.isNotEmpty()) throw GradleException("The release class path has $found.")
        logger.lifecycle("The release class path has no ${forbidden.joinToString()}. Projects: ${paths.get().joinToString()}")
    }
}

// Only the manifest label reads a string through Android resources (see app:android).
val copyStringRes = tasks.register<Copy>("copyStringRes") {
    from(rootProject.layout.projectDirectory.dir("core/resources/src/commonMain/composeResources/values")) {
        include("strings_probe.xml")
    }
    into(layout.buildDirectory.dir("copiedRes/values"))
}

tasks.named("preBuild") {
    dependsOn(copyStringRes)
}
