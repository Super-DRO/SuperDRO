package org.superdro.validation.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.validation.Traceability

/**
 * A review finds the SnapshotPB patterns in the code. The test is a scan of the source tree and
 * of the build files:
 *
 * - The `composite/` build logic with its published jar, the version catalog, the typesafe project
 *   accessors, and the Gradle properties.
 * - The Kodein DI layers: `coreDiModule` and `databaseModule` in `core:di`, `platformModule` in
 *   `platform:android:di`, and the graph of the `Application` with `DI.lazy`.
 * - The store pattern with `StoreIOProvider` and atomic JSON writes.
 * - The presenters in place of the thin ViewModels: each presenter is a plain
 *   Kotlin class with a coroutine scope and a `StateFlow` of its state. No class uses `ViewModel`.
 * - The debug seam `DebugAdapter` with `FakeAdapter` in the debug build only.
 * - Arbor logs and `localizationCheck` with one string file for each work area in each locale.
 */
class SnapshotPbPatternsTest {

    private val root: File = Traceability.rootDir

    private fun text(path: String): String = root.resolve(path).readText()

    private fun kotlinFiles(vararg directories: String): List<File> = directories.map(root::resolve).flatMap { directory ->
        directory.walkTopDown().onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
    }

    private fun mainSources(): List<File> = kotlinFiles("core", "platform", "app").filter { file ->
        val path = file.invariantSeparatorsPath
        "/src/" in path && !Regex("/src/[A-Za-z]*([Tt]est)/").containsMatchIn(path)
    }

    @Test
    fun theCompositeBuildLogicTheCatalogAndTheTypesafeAccessors() {
        assertTrue(root.resolve("composite/build.gradle.kts").isFile, "composite/ has its own build")
        assertTrue(root.resolve("composite/m2").isDirectory, "the build logic is published to composite/m2")
        assertTrue(root.resolve("composite/src/main").isDirectory)
        val settings = text("settings.gradle.kts")
        assertTrue("enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")" in settings)
        assertTrue("maven { url = uri(\"composite/m2\") }" in settings)
        assertTrue("includeBuild" !in settings.lines().filterNot { line -> line.trimStart().startsWith("//") }.joinToString("\n"))
        assertTrue(root.resolve("gradle/libs.versions.toml").isFile)
        assertTrue(root.resolve("gradle.properties").isFile)
        assertTrue("alias(libs.plugins.composite)" in text("build.gradle.kts"))
        // Each module build file uses the catalog and the typesafe accessors, not plain coordinates.
        val builds = root.walkTopDown().onEnter { it.name != "build" && it.name != "composite" && !it.name.startsWith(".") }
            .filter { file -> file.name == "build.gradle.kts" && file.parentFile != root }.toList()
        assertTrue(builds.size >= 30, "module build files: ${builds.size}")
        val plain = builds.filter { file -> Regex("""(implementation|api)\("[a-z]""").containsMatchIn(file.readText()) }
        assertEquals(emptyList(), plain.map { it.relativeTo(root).path }, "build files with a plain coordinate")
        val projectStrings = builds.filter { file -> "project(\":" in file.readText() }
        assertEquals(emptyList(), projectStrings.map { it.relativeTo(root).path }, "build files with project(\":...\") in place of the accessors")
    }

    @Test
    fun theKodeinLayers() {
        val core = kotlinFiles("core/di/src/commonMain").joinToString("\n") { it.readText() }
        assertTrue("val coreDiModule = DI.Module(\"coreDiModule\")" in core)
        assertTrue("val databaseModule = DI.Module(\"databaseModule\")" in core)
        assertTrue("import org.kodein.di" in core)
        val platform = kotlinFiles("platform/android/di/src/main").joinToString("\n") { it.readText() }
        assertTrue("val platformModule = DI.Module(\"platformModule\")" in platform)
        val application = kotlinFiles("app/android/src/main").joinToString("\n") { it.readText() }
        assertTrue("DI.lazy" in application && "DIAware" in application, "the Application builds the graph with DI.lazy")
        // core:ui does not depend on core:di.
        assertTrue("projects.core.di" !in text("core/ui/build.gradle.kts"))
    }

    @Test
    fun theStorePatternWithStoreIoProviderAndAtomicWrites() {
        val store = kotlinFiles("core/store/src/commonMain").associateBy { it.name }
        assertTrue("interface StoreIOProvider" in store.getValue("StoreIOProvider.kt").readText())
        assertTrue("fun StoreIOProvider.writeAtomically" in store.getValue("StoreFiles.kt").readText())
        // The JSON stores hold a StateFlow, change it with update, and serialize the file work with a Mutex.
        val base = store.getValue("JsonFileStore.kt").readText()
        assertTrue("StateFlow" in base && "Mutex" in base && "writeAtomically" in base && ".update" in base, "JsonFileStore has the store pattern")
        listOf("AppSettingsStore.kt", "TabStore.kt").forEach { name ->
            assertTrue(": JsonFileStore<" in store.getValue(name).readText(), "$name is a JsonFileStore")
        }
    }

    @Test
    fun eachPresenterIsAPlainClassWithAScopeAndAStateFlowAndNoClassUsesViewModel() {
        val presenter = Regex("""\bclass\s+(\w+Presenter)\b""")
        val ui = kotlinFiles("core/ui/src/commonMain")
        val found = ui.flatMap { file ->
            val source = file.readText()
            presenter.findAll(source).map { match -> Triple(match.groupValues[1], file, source) }.toList()
        }.filter { (name, _, _) -> name != "ScopedPresenter" }
        assertTrue(found.size >= 50, "presenters: ${found.size}")
        // A presenter extends ScopedPresenter (a CoroutineScope with a SupervisorJob), implements Presenter, or extends a
        // base presenter of this list.
        val noScope = found.filterNot { (name, _, source) ->
            Regex("""class\s+$name\b[\s\S]{0,3000}?\)\s*:\s*[^{]*?\w*Presenter\b""").containsMatchIn(source)
        }
        assertEquals(emptyList(), noScope.map { it.first }, "presenters with no scope")
        // Each presenter with a UI state gives it as a StateFlow. The functions holder of a tab keeps only the undo
        // offers of the tab and has no state of its own.
        val noState = found.filterNot { (_, _, source) -> Regex("""^\s*(override\s+)?val\s+\w+\s*:\s*StateFlow<""", RegexOption.MULTILINE).containsMatchIn(source) }
        assertEquals(listOf("FunctionTabPresenter"), noState.map { it.first }, "presenters with no StateFlow")
        val viewModels = mainSources().filter { file ->
            val source = file.readText()
            "androidx.lifecycle.ViewModel" in source || "androidx.lifecycle.viewmodel" in source || Regex(""":\s*ViewModel\(""").containsMatchIn(source)
        }
        assertEquals(emptyList(), viewModels.map { it.relativeTo(root).path })
        assertTrue("interface Presenter" in text("core/ui/src/commonMain/kotlin/org/superdro/core/ui/presenter/Presenter.kt"))
    }

    @Test
    fun theDebugSeamAndTheFakeAdapterInTheDebugBuildOnly() {
        val seam = kotlinFiles("core/debug-adapter/src/commonMain").joinToString("\n") { it.readText() }
        assertTrue("interface DebugAdapter" in seam && "NoDebugAdapter" in seam)
        val app = text("app/android/build.gradle.kts")
        assertTrue("debugImplementation(projects.core.fakeAdapter)" in app)
        assertTrue(Regex("""^\s*implementation\(projects\.core\.fakeAdapter\)""", RegexOption.MULTILINE).find(app) == null, "no fake adapter in the release build")
    }

    @Test
    fun arborLogsAndTheLocalizationCheckWithOneStringFileForEachAreaInEachLocale() {
        assertTrue("arbor" in text("gradle/libs.versions.toml"))
        assertTrue(mainSources().count { file -> "com.toxicbakery.logging.Arbor" in file.readText() } >= 20, "the modules log with Arbor")
        val resources = text("core/resources/build.gradle.kts")
        assertTrue("register<LocalizationCheckTask>(\"localizationCheck\")" in resources)
        val strings = root.resolve("core/resources/src/commonMain/composeResources")
        val base = strings.resolve("values").listFiles().orEmpty().filter { it.name.startsWith("strings") }.map { it.name }.toSet()
        assertTrue(base.size >= 20, "one string file for each work area: $base")
        listOf("values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl").forEach { locale ->
            val files = strings.resolve(locale).listFiles().orEmpty().filter { it.name.startsWith("strings") }.map { it.name }.toSet()
            assertEquals(base, files, "$locale has the string files of the base")
        }
    }
}
