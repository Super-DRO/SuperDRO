rootProject.name = "SuperDRO"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // The build uses `composite` as a published jar from composite/m2, not with includeBuild,
    // because includeBuild breaks the build cache that CI uses. The cost of that choice: a change
    // in composite/src with no republish leaves an old jar in place with no message.
    //
    // A version that contains a digest of the build logic removes that failure: the requested
    // coordinate is in composite/m2 only when somebody published the current source. Thus a
    // republish that you forget fails at once with "plugin not found".
    //
    // Keep buildLogicVersion() identical to the copy in composite/build.gradle.kts. Only the
    // indentation and the `java.security.` prefix are different. BuildLogicVersionParityTest in composite compares the copies.
    fun buildLogicVersion(root: File): String {
        val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
            .map { name -> root.resolve(name) } +
            root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        sources.filter { file -> file.exists() }
            .sortedBy { file -> file.toRelativeString(root).replace(File.separatorChar, '/') }
            .forEach { file ->
                digest.update(
                    file.toRelativeString(root).replace(File.separatorChar, '/').toByteArray()
                )
                digest.update(0)
                digest.update(file.readBytes())
            }
        return "1.0-" + digest.digest().joinToString("") { value: Byte -> "%02x".format(value) }.take(12)
    }

    val compositeVersion = buildLogicVersion(File(rootDir, "composite"))

    repositories {
        maven { url = uri("composite/m2") }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // The version in gradle/libs.versions.toml is a placeholder. The digest decides.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "composite") useVersion(compositeVersion)
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JitPack gives only usb-serial-for-android. The filter keeps
        // each other group away from this repository.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.mik3y")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The modules of SuperDRO. The package of a module is `org.superdro` and the
// module path, for example `org.superdro.core.protocol`.
include(
    ":app:adapter-probe",
    ":app:android",
    ":app:android:baseline",
    ":app:desktop",
    ":core:adapter-probe",
    ":core:app-version",
    ":core:camera",
    ":core:connection",
    ":core:connection-ble",
    ":core:connection-tcp",
    ":core:data-output",
    ":core:debug-adapter",
    ":core:di",
    ":core:drawing",
    ":core:fake-adapter",
    ":core:functions",
    ":core:mcp",
    ":core:model",
    ":core:protocol",
    ":core:readout",
    ":core:resources",
    ":core:store",
    ":core:ui",
    ":core:util",
    ":platform:android:bluetooth",
    ":platform:android:camera",
    ":platform:android:di",
    ":platform:android:network",
    ":platform:android:service",
    ":platform:android:store",
    ":platform:android:usb",
    ":platform:desktop:di",
    ":platform:desktop:serial",
    ":platform:desktop:store",
    ":validation",
)
