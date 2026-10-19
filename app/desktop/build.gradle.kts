import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.packagingVersion

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The desktop app of SuperDRO: main() with the
// DI graph, one window with App(), the UI sounds, and the file picker. The common code runs with
// only the new platform shells of platform:desktop:*.
kotlin {
    configureOptIns()
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                implementation(projects.core.connection)
                implementation(projects.core.debugAdapter)
                implementation(projects.core.di)
                // The desktop app has no build types. Only the argument --demo binds the fake
                // adapter. With no --demo the graph binds NoDebugAdapter.
                implementation(projects.core.fakeAdapter)
                implementation(projects.core.model)
                implementation(projects.core.resources)
                implementation(projects.core.store)
                implementation(projects.core.ui)
                implementation(projects.core.util)
                implementation(projects.platform.desktop.di)
                implementation(projects.platform.desktop.serial)
                implementation(projects.platform.desktop.store)

                implementation(compose.desktop.currentOs)
                implementation(libs.arbor)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqldelight.runtime)
            }
        }
        jvmTest {
            dependencies {
                implementation(projects.core.camera)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // runComposeUiTest for the window content.
                implementation(libs.compose.ui.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.superdro.app.desktop.MainKt"

        nativeDistributions {
            // A plain package for each system. The desktop app has no updater and no code signature.
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "SuperDRO"
            // The installers accept only `major.minor.patch`: the `-dev` part goes away. A tree with
            // no tag has the version 0.0.0. The DMG and MSI checks refuse a major version 0 at the
            // configuration of the project, also on Linux. Thus a package of such a tree gets 1.0.0.
            packageVersion = packagingVersion(project.version.toString()).let { version ->
                if (version.substringBefore('.') == "0") "1.0.0" else version
            }
            description = "SuperDRO digital readout for TouchDRO compatible adapters"
            vendor = "SuperDRO"
            licenseFile.set(rootProject.file("LICENSE"))
            // The modules of `./gradlew :app:desktop:suggestRuntimeModules` (java.desktop is always in).
            // The JDBC driver of the database needs java.sql.
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")

            linux {
                // A PNG: the jpackage of Linux reads only a PNG. icon.png is a 512 x 512 render of
                // icon.svg. To make it again, run in app/desktop:
                //   inkscape icon/icon.svg --export-type=png --export-area-page \
                //     --export-width=512 --export-height=512 --export-filename=icon/icon.png
                iconFile.set(project.file("icon/icon.png"))
                packageName = "superdro"
            }
            windows {
                // convert icon/icon.png -define icon:auto-resize=256,128,64,48,32,16 icon/icon.ico
                iconFile.set(project.file("icon/icon.ico"))
                menuGroup = "SuperDRO"
            }
            macOS {
                bundleID = "org.superdro.desktop"
            }
        }
    }
}

// The window tests draw with no display.
tasks.named<Test>("jvmTest") {
    val screenshotDir = layout.buildDirectory.dir("screenshots")
    outputs.dir(screenshotDir).withPropertyName("screenshots")
    systemProperty("superdro.screenshotDir", screenshotDir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
