package org.superdro.composite.util

import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// The rule: each module that compiles Kotlin calls configureOptIns() in its kotlin {} block.
// Do not put @OptIn for these markers at call sites.
//
// The compiler gives a warning for an opt-in marker that is not on the compile class path. The
// two stdlib markers are always there. The kotlinx-serialization marker is there only when the
// module has kotlinx-serialization, directly or through an api dependency. The
// kotlinx-coroutines marker is there only when the module has kotlinx-coroutines. The Material 3
// marker is there only when the module has Compose Material 3. A module without one of these
// libraries sets the related parameter to false.

/**
 * Opts in to the experimental APIs that this project uses. See the rule at the top of this file.
 *
 * @param serialization Set to false when kotlinx-serialization is not on the compile class
 *   path of the module. The compiler then does not give a warning for an unresolved marker.
 * @param coroutines Set to false when kotlinx-coroutines is not on the compile class path of
 *   the module.
 * @param material3 Set to true when Compose Material 3 is on the compile class path of the
 *   module. The marker `ExperimentalMaterial3Api` covers `ModalBottomSheet` and `SheetState`.
 */
fun KotlinMultiplatformExtension.configureOptIns(
    serialization: Boolean = true,
    coroutines: Boolean = true,
    material3: Boolean = false,
) {
    compilerOptions { optIn.set(optInValues(serialization, coroutines, material3)) }
}

/** The Android form of [KotlinMultiplatformExtension.configureOptIns]. */
fun KotlinAndroidProjectExtension.configureOptIns(
    serialization: Boolean = true,
    coroutines: Boolean = true,
    material3: Boolean = false,
) {
    compilerOptions { optIn.set(optInValues(serialization, coroutines, material3)) }
}

/** The JVM form of [KotlinMultiplatformExtension.configureOptIns], for the module `:validation`. */
fun KotlinJvmProjectExtension.configureOptIns(
    serialization: Boolean = true,
    coroutines: Boolean = true,
    material3: Boolean = false,
) {
    compilerOptions { optIn.set(optInValues(serialization, coroutines, material3)) }
}

internal fun optInValues(
    serialization: Boolean,
    coroutines: Boolean = true,
    material3: Boolean = false,
): List<String> =
    stdlibOptIns +
        (if (serialization) listOf(SERIALIZATION_OPT_IN) else emptyList()) +
        (if (coroutines) listOf(COROUTINES_OPT_IN) else emptyList()) +
        (if (material3) listOf(MATERIAL3_OPT_IN) else emptyList())

private val stdlibOptIns = listOf(
    "kotlin.ExperimentalUnsignedTypes",
    "kotlin.uuid.ExperimentalUuidApi",
)

private const val SERIALIZATION_OPT_IN = "kotlinx.serialization.ExperimentalSerializationApi"

private const val COROUTINES_OPT_IN = "kotlinx.coroutines.ExperimentalCoroutinesApi"

private const val MATERIAL3_OPT_IN = "androidx.compose.material3.ExperimentalMaterial3Api"
