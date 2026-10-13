package org.superdro.composite.util

/**
 * The Android SDK levels of all Android modules of SuperDRO. Each Android build file reads its
 * values from this object. Do not write the numbers in a build file.
 *
 * - [COMPILE]: 37. `com.osmerion.sqlite.android:sqlite-android` 0.14.0 declares
 *   `minCompileSdk=37`. AGP 9.1.1 supports API 37.
 * - [MIN]: 24. BlueFalcon 3.7.14 declares 24.
 * - [TARGET]: 36.
 *
 * A change to this file changes the digest of the build logic. Run
 * `./gradlew -p composite clean test publish` after the change.
 */
object AndroidSdk {
    const val COMPILE: Int = 37
    const val MIN: Int = 24
    const val TARGET: Int = 36
}
