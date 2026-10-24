import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinJvm)
    // The UI validation (package ui) runs Compose UI tests on the JVM.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The acceptance tests of SuperDRO. The module has only tests. The tests check the
// requirements, the defects, and the rules of all modules.
kotlin {
    configureOptIns(serialization = false)
}

private class SystemPropertyArgs(
    private val args: List<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = args
}

val commonTestDeps = listOf<Any>(
    projects.core.model,
    projects.core.util,
    libs.kotlin.test,
    libs.kotlin.testJunit,
    libs.kotlinx.coroutines.test,
    libs.kotlinx.datetime,
)

val functionsTestDeps = commonTestDeps + listOf<Any>(
    projects.core.readout,
    projects.core.functions,
    projects.core.drawing,
    projects.core.camera,
)

val protocolTestDeps = commonTestDeps + listOf<Any>(
    projects.core.readout,
    projects.core.protocol,
    projects.core.connection,
    projects.core.connectionTcp,
    projects.core.connectionBle,
    projects.core.fakeAdapter,
)

val readoutTestDeps = commonTestDeps + listOf<Any>(
    projects.core.readout,
    projects.core.functions,
    projects.core.store,
    libs.sqldelight.jdbc,
    libs.sqlite.jdbc,
)

val storeTestDeps = commonTestDeps + listOf<Any>(
    projects.core.readout,
    projects.core.functions,
    projects.core.store,
    projects.core.drawing,
    projects.core.camera,
    projects.core.dataOutput,
    projects.core.connection,
    libs.sqldelight.jdbc,
    libs.sqlite.jdbc,
    libs.bouncycastle.tls,
)

// The MCP acceptance tests run the real server through the full DI graph of core:di on a
// loopback socket, with the database in memory and the fake adapter for an online machine.
val mcpTestDeps = commonTestDeps + listOf<Any>(
    projects.core.connection,
    projects.core.dataOutput,
    projects.core.mcp,
    projects.core.readout,
    projects.core.drawing,
    projects.core.store,
    projects.core.ui,
    projects.core.di,
    projects.core.debugAdapter,
    projects.core.fakeAdapter,
    projects.core.appVersion,
    libs.kotlinx.serialization.json,
    libs.bouncycastle.tls,
    libs.sqldelight.jdbc,
    libs.sqlite.jdbc,
    libs.kodein,
)

val projectTestDeps = commonTestDeps + listOf<Any>(
    projects.core.readout,
    projects.core.store,
    projects.core.connection,
    projects.core.protocol,
    projects.core.connectionTcp,
    projects.core.connectionBle,
    projects.core.fakeAdapter,
    libs.sqldelight.jdbc,
    libs.sqlite.jdbc,
)

dependencies {
    testImplementation(projects.core.model)
    testImplementation(projects.core.util)
    // The validation of the readout, the functions and the geometry, and the store, the files, the data output,
    // and the camera (packages readout, functions, and store).
    testImplementation(projects.core.readout)
    testImplementation(projects.core.functions)
    testImplementation(projects.core.store)
    testImplementation(projects.core.drawing)
    testImplementation(projects.core.camera)
    testImplementation(projects.core.dataOutput)
    testImplementation(projects.core.connection)
    // The validation of the MCP server (package mcp). The version is in the server info.
    testImplementation(projects.core.mcp)
    testImplementation(projects.core.appVersion)
    testImplementation(libs.sqldelight.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.bouncycastle.tls)
    testImplementation(projects.core.protocol)
    testImplementation(projects.core.connectionTcp)
    testImplementation(projects.core.connectionBle)
    testImplementation(projects.core.fakeAdapter)
    // The validation of the UI requirements (package ui): App() and the screens of core:ui with the
    // full DI graph, fake sessions, and the fake adapter.
    testImplementation(projects.core.ui)
    testImplementation(projects.core.di)
    testImplementation(projects.core.debugAdapter)
    testImplementation(projects.core.resources)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.runtime)
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.compose.material3)
    testImplementation(libs.compose.components.resources)
    testImplementation(libs.androidx.lifecycle.runtimeCompose)
    testImplementation(libs.kodein)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

fun domainRuntimeClasspath(name: String, deps: List<Any>) =
    configurations.create("${name}TestRuntimeClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        val base = configurations.testRuntimeClasspath.get()
        base.attributes.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attributes.attribute(key as Attribute<Any>, base.attributes.getAttribute(key)!!)
        }
        deps.forEach { dep -> project.dependencies.add(this.name, dep) }
    }

val functionsRuntime = domainRuntimeClasspath("functions", functionsTestDeps)
val protocolRuntime = domainRuntimeClasspath("protocol", protocolTestDeps)
val readoutRuntime = domainRuntimeClasspath("readout", readoutTestDeps)
val storeRuntime = domainRuntimeClasspath("store", storeTestDeps)
val mcpRuntime = domainRuntimeClasspath("mcp", mcpTestDeps)
val projectRuntime = domainRuntimeClasspath("project", projectTestDeps)

val root = rootDir
val parallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

fun registerDomainTest(
    taskName: String,
    classIncludes: List<String>,
    classExcludes: List<String> = emptyList(),
    runtimeClasspath: org.gradle.api.file.FileCollection,
    forks: Int = parallelForks,
    configureInputs: Test.() -> Unit = {},
): org.gradle.api.tasks.TaskProvider<Test> {
    val syncClasses = tasks.register<Sync>("sync${taskName.replaceFirstChar(Char::uppercase)}Classes") {
        from(sourceSets.test.map { it.output.classesDirs }) {
            include(classIncludes)
            exclude(classExcludes)
        }
        into(layout.buildDirectory.dir("domain-test-classes/$taskName"))
    }
    return tasks.register<Test>(taskName) {
        testClassesDirs = files(syncClasses)
        classpath = files(syncClasses, tasks.named("processTestResources"), runtimeClasspath)
        maxParallelForks = forks
        testLogging { showStandardStreams = true }
        systemProperty("java.awt.headless", "true")
        systemProperty("skiko.renderApi", "SOFTWARE")
        val uiScreenshotDir = layout.buildDirectory.dir("ui-screenshots/$taskName")
        outputs.dir(uiScreenshotDir).withPropertyName("uiScreenshots")
        val screenshotPath = uiScreenshotDir.get().asFile.absolutePath
        val rootPath = root.absolutePath
        jvmArgumentProviders.add(
            SystemPropertyArgs(
                listOf(
                    "-Dsuperdro.rootDir=$rootPath",
                    "-Dsuperdro.uiScreenshotDir=$screenshotPath",
                ),
            ),
        )
        configureInputs()
    }
}

val testFunctions = registerDomainTest(
    taskName = "testFunctions",
    classIncludes = listOf("org/superdro/validation/functions/**", "org/superdro/validation/Traceability*"),
    runtimeClasspath = functionsRuntime,
) {
    inputs.files(
        fileTree(root) { include("core/drawing/src/commonMain/**/*.kt") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("drawingSources")
}

val testProtocol = registerDomainTest(
    taskName = "testProtocol",
    classIncludes = listOf("org/superdro/validation/protocol/**", "org/superdro/validation/Traceability*"),
    runtimeClasspath = protocolRuntime,
) {
    inputs.files(
        fileTree(root) {
            include(
                "core/resources/src/commonMain/composeResources/values/strings_connection.xml",
                "core/resources/src/commonMain/composeResources/values/strings_app.xml",
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("protocolStrings")
}

val testReadout = registerDomainTest(
    taskName = "testReadout",
    classIncludes = listOf("org/superdro/validation/readout/**", "org/superdro/validation/Traceability*"),
    runtimeClasspath = readoutRuntime,
) {
    inputs.files(
        fileTree(root) { include("core/resources/src/commonMain/composeResources/values*/*.xml") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("readoutStrings")
}

val testStore = registerDomainTest(
    taskName = "testStore",
    classIncludes = listOf("org/superdro/validation/store/**", "org/superdro/validation/Traceability*"),
    runtimeClasspath = storeRuntime,
) {
    inputs.files(
        fileTree(root) {
            include(
                "core/store/build.gradle.kts",
                "core/store/src/commonMain/sqldelight/**",
                "core/data-output/consumer-rules.pro",
                "core/resources/src/commonMain/composeResources/files/**",
                "core/resources/src/commonMain/composeResources/values/strings_data_output.xml",
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("storeFiles")
}

val testMcp = registerDomainTest(
    taskName = "testMcp",
    classIncludes = listOf("org/superdro/validation/mcp/**", "org/superdro/validation/Traceability*"),
    runtimeClasspath = mcpRuntime,
) {
    inputs.files(
        fileTree(root) {
            include(
                "core/mcp/src/commonMain/**/*.kt",
                "core/model/src/commonMain/**/McpConfig.kt",
                "core/ui/src/commonMain/kotlin/org/superdro/core/ui/mcp/**/*.kt",
                "core/di/src/commonMain/**/*.kt",
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mcpSources")
}

val testProject = registerDomainTest(
    taskName = "testProject",
    classIncludes = listOf(
        "org/superdro/validation/ArchitectureTest*",
        "org/superdro/validation/project/**",
        "org/superdro/validation/Traceability*",
        "org/superdro/validation/store/StoreHarness*",
        "org/superdro/validation/store/StoreFixtures*",
        "org/superdro/validation/protocol/SessionHarness*",
        "org/superdro/validation/protocol/QueueDriver*",
        "org/superdro/validation/protocol/ScriptFactory*",
        "org/superdro/validation/protocol/ScriptTransport*",
    ),
    runtimeClasspath = projectRuntime,
) {
    inputs.file(root.resolve("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.resolve("gradle.properties")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.resolve("settings.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.resolve("build.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.resolve("validation/build.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.resolve(".github/workflows/validation.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree(root) {
            include(
                "core/**/build.gradle.kts",
                "platform/**/build.gradle.kts",
                "app/**/build.gradle.kts",
                "core/**/src/**/*.kt",
                "platform/**/src/**/*.kt",
                "app/**/src/**/*.kt",
                "core/resources/src/commonMain/composeResources/**",
            )
            exclude("**/build/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("projectSources")
}

// Each test class of the two screen tasks below runs on its own fork. The long tests are in
// separate classes (ScreensOpen*, Screenshot*ValidationTest), so that the forks share the work.
val uiRuntime = configurations.testRuntimeClasspath
val uiHelperClasses = listOf(
    "org/superdro/validation/ui/AppGraph*",
    "org/superdro/validation/ui/ComposableSingletons*",
    "org/superdro/validation/ui/FakeConnections*",
    "org/superdro/validation/ui/FakeSession*",
    "org/superdro/validation/ui/MainScreenSamples*",
    "org/superdro/validation/ui/PresenterRig*",
    "org/superdro/validation/ui/UiFixtures*",
    "org/superdro/validation/ui/Wcag*",
    "org/superdro/validation/Traceability*",
)

val testUi = registerDomainTest(
    taskName = "testUi",
    classIncludes = listOf("org/superdro/validation/ui/**", "org/superdro/validation/Traceability*"),
    // The screen table tests (Screens*) and the screenshot tests (Screenshot*) have their own tasks.
    classExcludes = listOf("org/superdro/validation/ui/Screens*"),
    runtimeClasspath = uiRuntime.get(),
) {
    inputs.files(
        fileTree(root) {
            include(
                "core/ui/src/commonMain/kotlin/**/*.kt",
                "app/android/src/main/AndroidManifest.xml",
                "core/resources/src/commonMain/composeResources/**",
            )
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uiSources")
}

val testUiScreens = registerDomainTest(
    taskName = "testUiScreens",
    classIncludes = listOf(
        "org/superdro/validation/ui/ScreensValidationTest*",
        "org/superdro/validation/ui/ScreensOpen*",
        "org/superdro/validation/ui/ScreensEntries*",
    ) + uiHelperClasses,
    runtimeClasspath = uiRuntime.get(),
) {
    inputs.files(
        fileTree(root) { include("core/resources/src/commonMain/composeResources/**") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uiResources")
}

val testUiScreenshots = registerDomainTest(
    taskName = "testUiScreenshots",
    classIncludes = listOf("org/superdro/validation/ui/Screenshot*") + uiHelperClasses,
    runtimeClasspath = uiRuntime.get(),
) {
    inputs.files(
        fileTree(root) { include("core/resources/src/commonMain/composeResources/**") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uiResources")
}

val domainTests = listOf(
    testFunctions,
    testProtocol,
    testReadout,
    testStore,
    testMcp,
    testProject,
    testUi,
    testUiScreens,
    testUiScreenshots,
)

val hasCommandLineTestFilter = gradle.startParameter.taskRequests.any { "--tests" in it.args }

tasks.test {
    if (!hasCommandLineTestFilter) {
        dependsOn(domainTests)
        enabled = false
    }
    testLogging { showStandardStreams = true }
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    val uiScreenshotDir = layout.buildDirectory.dir("ui-screenshots/test")
    outputs.dir(uiScreenshotDir).withPropertyName("uiScreenshots")
    val screenshotPath = uiScreenshotDir.get().asFile.absolutePath
    val rootPath = root.absolutePath
    jvmArgumentProviders.add(
        SystemPropertyArgs(
            listOf(
                "-Dsuperdro.rootDir=$rootPath",
                "-Dsuperdro.uiScreenshotDir=$screenshotPath",
            ),
        ),
    )
}
