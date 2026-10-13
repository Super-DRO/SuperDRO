import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    // Only this module applies the SQLDelight plugin and calls the generated queries.
    alias(libs.plugins.sqldelight)
}

kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.store")
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.model)
                // The one map text grammar (core:readout, encoder.MapText). core:readout
                // has no coroutines and no Compose.
                implementation(projects.core.readout)

                api(libs.sqldelight.runtime)
                implementation(libs.arbor)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                api(libs.kotlinx.io)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.json.io)
                implementation(libs.sqldelight.coroutines)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // The JDBC driver goes only into the tests. The desktop app gives its own driver.
        val jvmTest by getting {
            dependencies {
                implementation(libs.sqldelight.jdbc)
                // SQLite 3.53.4 on the JVM. Change it together with sqlite-android.
                implementation(libs.sqlite.jdbc)
            }
        }
    }
}

sqldelight {
    databases {
        create("SuperDroDatabase") {
            packageName.set("org.superdro.core.store.db")
            // The SQL must agree with the bundled SQLite 3.53.4, not with the SQLite of the
            // device (API 24 has 3.9). Thus the dialect line is explicit.
            dialect(libs.sqldelight.dialect)
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
            generateAsync.set(false)
        }
    }
}

// The migration check runs in `check`, and thus in CI.
tasks.named("check") {
    dependsOn("verifySqlDelightMigration")
}
