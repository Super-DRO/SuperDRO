package org.superdro.validation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the module structure and the dependency rules against the build files, and the source rules
 * against the source tree.
 */
class ArchitectureTest {

    private val root = Traceability.rootDir

    /** The modules of `settings.gradle.kts`, for example `core:ui`. */
    private val modules: List<String> = Regex("""":([a-z0-9:-]+)"""")
        .findAll(root.resolve("settings.gradle.kts").readText().substringAfter("include("))
        .map { match -> match.groupValues[1] }
        .toList()

    private fun buildFile(module: String): File = root.resolve(module.replace(':', '/')).resolve("build.gradle.kts")

    /** Changes a typesafe accessor (`projects.core.dataOutput`) into a module path (`core:data-output`). */
    private fun accessorToModule(accessor: String): String =
        accessor.split('.').joinToString(":") { part -> part.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() } }

    /** The project dependencies of [module] in its build file. */
    private fun projectDependencies(module: String): Set<String> =
        Regex("""projects\.([A-Za-z0-9.]+)""").findAll(buildFile(module).readText())
            .map { match -> accessorToModule(match.groupValues[1]) }
            .filter { dependency -> dependency in modules }
            .toSet()

    /** The project dependencies of [module] in its build file, also the dependencies of these, and so on. */
    private fun transitiveDependencies(module: String): Set<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque(projectDependencies(module))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (result.add(next)) queue += projectDependencies(next)
        }
        return result
    }

    /** The catalog libraries (`libs.xxx`) that the build file of [module] names, with no plugin and no version. */
    private fun libraries(module: String): Set<String> =
        Regex("""libs\.([A-Za-z0-9.]+)""").findAll(buildFile(module).readText())
            .map { match -> match.groupValues[1] }
            .filterNot { name -> name.startsWith("plugins.") || name.startsWith("versions.") }
            .toSet()

    private val coreModules get() = modules.filter { module -> module.startsWith("core:") }

    @Test
    fun theRepositoryHasTheSnapshotPbTree() {
        listOf("app", "core", "platform", "composite").forEach { name ->
            assertTrue(root.resolve(name).isDirectory, "The directory $name/ is missing.")
        }
        assertTrue(root.resolve("composite/m2").isDirectory, "composite/m2 is missing.")
        val expected = listOf(
            "core:util", "core:model", "core:protocol", "core:readout", "core:functions", "core:connection",
            "core:connection-ble", "core:connection-tcp", "core:data-output", "core:drawing", "core:camera",
            "core:debug-adapter", "core:fake-adapter", "core:adapter-probe", "core:store", "core:resources", "core:ui",
            "core:di", "core:app-version", "platform:android:bluetooth", "platform:android:usb",
            "platform:android:service", "platform:android:network", "platform:android:camera", "platform:android:store",
            "platform:android:di", "app:android", "app:android:baseline", "app:adapter-probe",
        )
        assertEquals(emptyList(), expected - modules.toSet(), "Modules that are missing.")
    }

    @Test
    fun eachCoreModuleIsKmpWithTheAndroidAndTheJvmTarget() {
        val problems = coreModules.filterNot { module ->
            val text = buildFile(module).readText()
            "kotlinMultiplatform" in text && "androidMultiplatformLibrary" in text && "superDroTargets(" in text
        }
        assertEquals(emptyList(), problems, "core modules without the KMP plugin and the two targets.")
        val ios = coreModules.filter { module -> Regex("""ios[A-Z]|wasmJs""").containsMatchIn(buildFile(module).readText()) }
        assertEquals(emptyList(), ios, "Targets: no iOS or wasm target.")
    }

    @Test
    fun coreDoesNotDependOnPlatformOrApp() {
        val problems = coreModules.flatMap { module ->
            projectDependencies(module).filter { it.startsWith("platform:") || it.startsWith("app:") }
                .map { "$module -> $it" }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun theUiDoesNotDependOnDiTransportsOrBlueFalcon() {
        val forbidden = setOf(
            "core:di", "core:connection-ble", "core:connection-tcp", "platform:android:bluetooth", "platform:android:usb",
        )
        assertEquals(emptySet(), transitiveDependencies("core:ui") intersect forbidden)
        val blueFalcon = (listOf("core:ui") + transitiveDependencies("core:ui"))
            .filter { module -> libraries(module).any { it.lowercase().startsWith("blue") } }
        assertEquals(emptyList(), blueFalcon, "BlueFalcon on the class path of core:ui.")
    }

    @Test
    fun thePureModulesHaveNoCoroutinesAndNoCompose() {
        val problems = listOf("core:protocol", "core:readout", "core:functions", "core:drawing").flatMap { module ->
            (listOf(module) + transitiveDependencies(module)).flatMap { each ->
                libraries(each).filter { it.startsWith("kotlinx.coroutines") || it.startsWith("compose") }
                    .map { "$module via $each: $it" }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun aTransportModuleDependsOnlyOnTheConnectionContract() {
        val allowed = setOf("core:connection", "core:model", "core:util")
        val problems = listOf(
            "core:connection-tcp", "core:connection-ble", "platform:android:bluetooth", "platform:android:usb",
        ).flatMap { module -> (projectDependencies(module) - allowed).map { "$module -> $it" } }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun theProbeAppHasNoUiStoreOrDataOutput() {
        val found = transitiveDependencies("app:adapter-probe") intersect setOf("core:ui", "core:store", "core:data-output")
        assertEquals(emptySet(), found)
    }

    private fun sources(): List<File> =
        listOf("core", "platform", "app").map(root::resolve).flatMap { directory ->
            directory.walkTopDown().onEnter { it.name != "build" && !it.name.startsWith(".") && it.name != "third_party" }
                .filter { file -> file.isFile && (file.extension == "kt" || file.extension == "java") }
                .filter { file -> "/src/" in file.invariantSeparatorsPath && !Traceability.isTestSource(file) }
                .toList()
        }

    @Test
    fun theAppHasNoXmlLayoutsAndNoOwnViewClasses() {
        val layouts = listOf("core", "platform", "app").map(root::resolve).flatMap { directory ->
            directory.walkTopDown().onEnter { it.name != "build" }
                .filter { file -> file.isDirectory && file.name.startsWith("layout") && file.parentFile.name == "res" }
                .toList()
        }
        assertEquals(emptyList(), layouts.map { it.relativeTo(root).path }, "XML layout directories.")
        // The UVC video uses a SurfaceView in an AndroidView. That is the one permitted
        // View class.
        val viewClass = Regex("""class\s+\w+[^{\n]*:\s*(android\.view\.)?(View|ViewGroup|FrameLayout|LinearLayout|TextureView)\(""")
        val views = sources().filter { file -> viewClass.containsMatchIn(file.readText()) }
            .map { it.relativeTo(root).path }
        assertEquals(emptyList(), views, "Android View classes of the app.")
    }

    @Test
    fun noViewModelRoomDataStoreWorkManagerCameraXOrPlayServices() {
        val forbiddenImports = listOf(
            "androidx.lifecycle.ViewModel", "androidx.lifecycle.viewmodel", "androidx.room", "androidx.datastore",
            "androidx.work", "androidx.camera", "com.google.android.gms",
        )
        val problems = sources().flatMap { file ->
            val text = file.readText()
            forbiddenImports.filter { name -> "import $name" in text }.map { "${file.relativeTo(root).path}: $it" }
        }
        assertEquals(emptyList(), problems)
        val catalog = root.resolve("gradle/libs.versions.toml").readText()
        listOf("androidx.room", "androidx.datastore", "androidx.work", "androidx.camera", "com.google.android.gms")
            .forEach { group -> assertTrue("\"$group" !in catalog, "The catalog names $group.") }
    }
}
