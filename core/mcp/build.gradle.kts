import org.superdro.composite.util.configureOptIns
import org.superdro.composite.util.superDroTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// The MCP server: JSON-RPC, the Streamable HTTP transport on the sockets of core:data-output, the
// tool contract, the rules, and the secrets. Common code only. No Compose, no store, no Ktor server.
kotlin {
    configureOptIns()
    superDroTargets("org.superdro.core.mcp")
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The public API has the configuration, the request bus, the socket contracts,
                // and the coroutine types.
                api(projects.core.connection)
                api(projects.core.model)
                api(projects.core.util)
                api(projects.core.dataOutput)

                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
