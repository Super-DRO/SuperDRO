# SuperDRO

SuperDRO is a DRO (digital readout) app for mills and lathes on an Android tablet. It shows the positions of the
machine axes from a DRO adapter. SuperDRO is compatible with the TouchDRO adapters: it uses the same text protocol
over Bluetooth Classic SPP, USB serial, and Wi-Fi TCP. SuperDRO connects to many machines at the same time, and a
foreground service keeps the links open in the background.

The app uses Kotlin Multiplatform and Compose Multiplatform. The common code has the Android target and the JVM
target. The repository also has the adapter probe app, which does the adapter tests on the tablet, and a desktop app
on the JVM.

The app code uses the Apache License 2.0 ([LICENSE](LICENSE)).

## Documents

| Document | Content |
|---|---|
| [CLAUDE.md](CLAUDE.md) | The work rules of the repository |
| [docs/versioning-and-release-plan.md](docs/versioning-and-release-plan.md) | The app version and the release workflows |
| [docs/thread-cutting-spike.md](docs/thread-cutting-spike.md) | The research and the design for the thread cutting tools |
| [docs/thread-cutting-plan.md](docs/thread-cutting-plan.md) | The multi-agent implementation plan for the thread cutting tools |
| [docs/mcp-proposal.md](docs/mcp-proposal.md) | The research, the design, the final tool list, and the manual checks of the MCP server |
| [docs/mcp-plan.md](docs/mcp-plan.md) | The multi-agent implementation plan for the MCP server |

## Status

The app has each function of the specification. `:validation:test` runs the acceptance tests. The manual checks on
the emulator and on a Samsung SM-X230 tablet with Android 16 pass. They include 30 minutes in the background and 30 minutes with the screen off with two links (R10),
the UVC camera, the data output in the PSK mode, and the tablet tests TT1 and TT2.

The main open work:

- The adapter tests T01 to T16 with a real adapter on the SPP, USB, and Wi-Fi TCP links, a record with a motion and a
  probe, and a check of SuperDRO with the adapter of the user. The user has no adapter for the tests now. The probe
  app passes all tests against the fake adapter and a TCP simulator. The firmware questions stay open until these
  tests.
- BLE: the transport is in the code, but the UI does not offer a BLE link, and no BLE adapter was tested (R4).
- The thread cut warning "X not referenced" (D135) does not show.
- MQTT has no setting for a broker with a custom CA.
- iOS and web apps are not built.
- Checks that are not recorded: the MQTT output and the PSK mode in a release APK on a device, and the start at boot
  after a reboot of the tablet.
- Small code cleanups.

## Modules

The package of a module is `org.superdro` and the module path, for example `org.superdro.core.protocol`. A module
path with a hyphen has no hyphen in its package, for example `org.superdro.core.dataoutput` for `core:data-output`.
The transport modules are an exception: `core:connection-ble` has the package `org.superdro.core.connection.ble`.

| Module | Content |
|---|---|
| `core:util` | Ring buffer, monotonic clock, byte helpers, the shared sound code, the `SecretStore` contract (P17) |
| `core:model` | The `@Serializable` models of the stored entities |
| `core:protocol` | The adapter protocol. No coroutines, no Compose. |
| `core:readout` | The input stage and the readout math. No coroutines, no Compose. |
| `core:functions` | Hole patterns, arcs, measurement, compensation, transforms. No coroutines, no Compose. |
| `core:connection` | The transport contract, the sessions, the session states, the request bus, the `NetworkInterfaces` contract (P15) |
| `core:connection-ble` | The BLE transport with BlueFalcon (the UI does not offer it yet) |
| `core:connection-tcp` | The Wi-Fi TCP transport with Ktor sockets |
| `core:data-output` | The live data output: TCP server, PSK mode, MQTT client |
| `core:mcp` | The MCP server: JSON-RPC, Streamable HTTP in two eras, the tool contract, the rules, the token |
| `core:drawing` | DXF import and export, drawings in nm. No coroutines, no Compose. |
| `core:camera` | The models and the rules of the USB camera |
| `core:debug-adapter` | The `DebugAdapter` seam and `NoDebugAdapter` |
| `core:fake-adapter` | The fake adapter of the debug builds and the tests |
| `core:adapter-probe` | The logic of the adapter probe app |
| `core:store` | The SQLDelight database `SuperDroDatabase`, the JSON stores, `StoreIOProvider` |
| `core:resources` | The strings in six languages, the readout fonts, the icons, `localizationCheck` |
| `core:ui` | `App()`, the theme, the screens, the presenters |
| `core:di` | The Kodein modules `coreDiModule` and `databaseModule` |
| `core:app-version` | The generated version stamp |
| `platform:android:bluetooth` | The SPP transport |
| `platform:android:usb` | The USB serial transport |
| `platform:android:service` | The connection service |
| `platform:android:network` | The network state and the interfaces of the data output |
| `platform:android:camera` | The UVC camera back end |
| `platform:android:store` | `StoreIOProviderAndroid` and the SQL driver with the bundled SQLite 3.53.4 |
| `platform:android:di` | The Kodein module `platformModule` |
| `platform:desktop:serial` | The SPP and USB serial transport of the desktop app (jSerialComm) |
| `platform:desktop:store` | The files, the SQL driver, and the secret store of the desktop app |
| `platform:desktop:di` | The Kodein module of the desktop app |
| `app:android` | The app (`org.superdro`) |
| `app:android:baseline` | The Baseline Profile generator |
| `app:adapter-probe` | The adapter probe app (`org.superdro.adapterprobe`) |
| `app:desktop` | The desktop app on the JVM |
| `validation` | The acceptance tests |
| `composite` | The build logic, a separate Gradle build |

## Build

You need a JDK 21 for the Gradle daemon (a JetBrains JDK, see `gradle/gradle-daemon-jvm.properties`) and the
Android SDK with the platform 37. Put the SDK path in `local.properties` (`sdk.dir=...`).

Many builds can run at the same time on one computer. Use these flags for each Gradle call, so that a build does not
use all the memory:

```bash
./gradlew --max-workers=4 -Dorg.gradle.jvmargs="-Xmx6g -XX:+UseParallelGC" \
  -Pkotlin.compiler.execution.strategy=in-process <tasks>
```

The examples below leave out the flags.

```bash
# The debug app and the adapter probe app
./gradlew :app:android:assembleDebug :app:adapter-probe:assembleDebug

# The JVM tests of all common modules, and the acceptance tests
./gradlew jvmTest :validation:test

# One area only: the MCP server (the UI tests of core:ui and the acceptance tests of validation)
./gradlew :core:ui:jvmTestMcp :validation:testMcp

# The checks of CI that a project build does not run
./gradlew :core:resources:localizationCheck :core:store:verifySqlDelightMigration

# The instrumented test of the bundled SQLite (a device or an emulator is necessary)
ANDROID_SERIAL=<device> ./gradlew :platform:android:store:connectedDebugAndroidTest

# The desktop app with the two demo machines
./gradlew :app:desktop:run --args=--demo
```

`./gradlew :core:ui:jvmTest` draws `App()` to PNG files in `core/ui/build/screenshots/`. Use
`ScreenshotRenderer` in a `jvmTest` of `core:ui` to see a composable with no emulator.

### Build logic

The build uses the plugin `composite` from `composite/m2`, not with `includeBuild`. The version of the plugin is a
SHA-256 digest of its source. After a change in `composite/`, do these steps:

1. Run `./gradlew -p composite clean test publish`.
2. Commit the new `composite/m2` tree with the change.

If you forget step 1, the root build stops with "plugin not found".

### Database

The schema is in `core/store/src/commonMain/sqldelight`. The file `databases/1.db` holds the schema version 1 and
does not change. Each schema change adds one migration file `<n>.sqm` in `migrations/`. `verifySqlDelightMigration`
runs in CI.

CAUTION: Do not use the SQL math functions (for example `acosh` or `radians`) or `percentile` in a `.sq` file. The
JVM SQLite has them, and the Android SQLite does not.

## Languages

All UI strings are in `core/resources/src/commonMain/composeResources`. Each work area has its own string file, for
example `values/strings_app.xml`. Each file has the same names in `values` (English), `values-es`, `values-fr`,
`values-de`, `values-pt-rBR`, and `values-nl`. A missing file or a missing name fails
`:core:resources:localizationCheck`.

## CI

`.github/workflows/validation.yml` runs two jobs: `android` (the APKs of both apps, the compile checks of the
instrumented tests and the Baseline Profile generator, and `lint`) and `jvm` (the tests of `composite`, `jvmTest`,
`:validation:test`, `localizationCheck`, and `verifySqlDelightMigration`).
