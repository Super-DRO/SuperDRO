package org.superdro.composite.util

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Each core/* module is a Kotlin Multiplatform module, and CI compiles
// commonMain for the Android target and the JVM target. Each core/*
// module calls superDroTargets() in its kotlin {} block, and applies the plugins
// `org.jetbrains.kotlin.multiplatform` and `com.android.kotlin.multiplatform.library`.

/**
 * Declares the two targets of a common module: `android` (the AGP Kotlin Multiplatform library
 * target) and `jvm`. The Android target gets the SDK levels of [AndroidSdk].
 *
 * @param namespace The Android namespace: `org.superdro` and the module path, for example
 *   `org.superdro.core.protocol`.
 * @param androidResources Set to true when the module has Compose resources. Compose
 *   Multiplatform then puts them into the Android assets (a font loads only from there).
 */
fun KotlinMultiplatformExtension.superDroTargets(namespace: String, androidResources: Boolean = false) {
    val android = (this as ExtensionAware).extensions.findByName(ANDROID_TARGET_EXTENSION)
        as? KotlinMultiplatformAndroidLibraryExtension
        ?: error(
            "superDroTargets() needs the plugin com.android.kotlin.multiplatform.library. " +
                "Apply it in the plugins {} block of the module."
        )
    android.namespace = namespace
    android.compileSdk = AndroidSdk.COMPILE
    android.minSdk = AndroidSdk.MIN
    if (androidResources) {
        android.androidResources { enable = true }
    }
    jvm()
}

/** The name of the extension that the AGP Kotlin Multiplatform library plugin adds to `kotlin {}`. */
private const val ANDROID_TARGET_EXTENSION = "android"
