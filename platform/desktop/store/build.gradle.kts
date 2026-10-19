import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The files of the desktop app: the
// application data directory of the user, StoreIOProviderJvm, the database file with
// JdbcSqliteDriver and SQLite 3.53.4, and the file SecretStore.
kotlin {
    configureOptIns(coroutines = false)
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                api(projects.core.store)
                api(projects.core.util)

                implementation(libs.arbor)
                implementation(libs.kotlinx.io)
                implementation(libs.sqldelight.jdbc)
                // SQLite 3.53.4 on the JVM. Change it together with sqlite-android.
                implementation(libs.sqlite.jdbc)
            }
        }
        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
