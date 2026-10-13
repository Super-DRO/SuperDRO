import java.security.MessageDigest

plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
    `maven-publish`
}

group = "org.superdro.composite"

// The published version contains a digest of this build logic. Thus the coordinate changes
// when the source changes. The root settings.gradle.kts calculates the same digest and asks
// for exactly that version. A republish that you forget then gives the error "plugin not
// found" at once. It does not give a jar that is out of date with no message.
//
// Keep buildLogicVersion() identical to the copy in the root settings.gradle.kts. Only the
// indentation and the `java.security.` prefix are different: in a project script, `java`
// is the Java plugin extension, so this copy imports MessageDigest. The two copies cannot
// share a file, because this build makes the plugin that the root build uses.
// BuildLogicVersionParityTest compares the two copies.
// A difference also stops the root build at once, because the versions do not agree.
//
// The digest covers build.gradle.kts, gradle.properties, settings.gradle.kts, and src/main.
// It does not cover src/test: a change to a test only does not change the jar, so it must not
// give each consumer a new coordinate. It also does not cover gradle/libs.versions.toml, so a
// change of a tool or dependency version still needs a manual republish.
version = buildLogicVersion(layout.projectDirectory.asFile)

fun buildLogicVersion(root: File): String {
    val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
        .map { name -> root.resolve(name) } +
        root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
    val digest = MessageDigest.getInstance("SHA-256")
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

gradlePlugin {
    plugins {
        register("composite") {
            id = "composite"
            displayName = "SuperDRO build logic"
            implementationClass = "org.superdro.composite.CompositePlugin"
        }
    }
}

dependencies {
    // compileOnly, so that this plugin does not put its own Kotlin Gradle plugin version on
    // the classpath of the root build. The root build.gradle.kts declares the Kotlin plugins,
    // the Android plugins, and `composite` in one plugins {} block, so they load in the same
    // class loader, and configureOptIns() finds the KGP classes of the root build at run time.
    compileOnly(libs.kotlin.gradle)
    // compileOnly for the same reason: superDroTargets() finds the AGP classes of the root build.
    compileOnly(libs.android.gradle.api)
    testImplementation(libs.kotlin.test)
}

// The version rules and the localization rules decide the result of a build, and they are
// easy to get wrong. Thus they have unit tests here.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven(uri("m2")) {
            name = "m2"
        }
    }
}

// The version contains a digest, so each change leaves the previous version behind, and `m2`
// grows with no limit. An empty repository before each publish keeps exactly one version in
// the tree. This also keeps the committed difference small.
val clearM2 = tasks.register<Delete>("clearM2") {
    delete(layout.projectDirectory.dir("m2"))
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(clearM2)
}
