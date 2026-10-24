# CLAUDE.md

This file gives the rules for all work in this repository. Read all of it before you change a file.

## Project

SuperDRO is a personal DRO (digital readout) app for Android tablets. It is compatible with the TouchDRO
adapters.

## Language and wording

- Write all comments, KDoc, documents, and commit messages in ASD-STE100 Simplified Technical English:
  short sentences, active voice, simple tenses, one instruction for each sentence.
- Code identifiers, protocol texts, and UI strings are technical names. The STE rules do not apply to them.
- Name TouchDRO only in text that states compatibility: a compatible adapter, protocol, or client, or a TouchDRO
  file format, import, or export. For example "Plain text (TouchDRO compatible)" or "Import a TouchDRO settings
  file". Do not compare SuperDRO with TouchDRO, and do not write about the history of TouchDRO.
- Never write that SuperDRO copies, clones, ports, or derives from TouchDRO, or that a part of SuperDRO is based on
  TouchDRO.
- SuperDRO contains no TouchDRO code, strings, layouts, icons, or sound files.

## Commits

- Do not add a `Co-Authored-By` trailer, a "Generated with" line, or any other mark of AI work.
- Write the commit message as a human author: a short subject, then a body in STE if necessary.
- Commit only on your own branch, in your own worktree. Never commit on `main`. Never merge, rebase, or push.
  The coordinator merges the branches.

## Definition of done

A work item is done only when all of these conditions are true:

- The JVM tests of the changed modules and the matching `:validation:test*` tasks pass.
- Before a push or a pull request update, `scripts/validate.sh` passes (or `scripts/validate.sh --android` when the change touches Android code, build logic, or resources).
- The coordinator merged the branch of the work item into `main`.
- The worktree of the work item does not exist. After the merge, the coordinator removes it with
  `git worktree remove <path>` and then runs `git worktree prune`.
- The local branch of the work item does not exist. After the merge, the coordinator deletes it with
  `git branch -d <branch>`.
- No empty worktree folder stays on the disk.

Use `git branch -d`, not `git branch -D`. The `-d` flag deletes only a merged branch. If `-d` fails, the branch
has commits that are not in `main`. Merge these commits, or ask the user before you delete the branch.

## Architecture rules

- Package root `org.superdro`. The package of a module is the root plus the module path, for example
  `org.superdro.core.protocol` for `core:protocol`.
- `core/*` must not depend on `platform/*` or `app/*`.
- `core:ui` must not depend on `core:di`, a transport module, or BlueFalcon.
- `core:protocol`, `core:readout`, `core:functions`, and `core:drawing` have no dependency on coroutines
  or Compose.
- Each link type has its own transport module. A transport module depends only on `core:connection` and its
  link library.
- `app:adapter-probe` does not depend on `core:ui`, `core:store`, `core:data-output`, or `core:mcp`.
- `core:mcp` depends only on `core:connection`, `core:model`, `core:util`, `core:data-output`, kotlinx-coroutines, and
  kotlinx-serialization. No Compose, no Ktor server, no store.
- Common modules declare only the `android` target and the `jvm` target. No iOS, no wasm.
- No androidx `ViewModel` class. Each screen has a presenter: a plain Kotlin class with a `CoroutineScope`
  and a `StateFlow` of its UI state. Composables are stateless.
- No Room, no DataStore, no WorkManager, no CameraX, no Google Play services.
- Use as few Google libraries as possible.
- Every dependency must have a license that agrees with Apache 2.0.

## Units and numbers

- Lengths: micrometers (µm) as `Double`. Angles: arc seconds as `Double`. Counts: `Long`.
- Drawing coordinates: integer nanometers (`Long`). Times: milliseconds.
- Never use `Float` for a position.

## Strings and languages

- All UI strings are in `core:resources` (`src/commonMain/composeResources`).
- Each work area has its own string file, for example `values/strings_readout.xml`. This prevents merge
  conflicts. Do not put your strings in a string file of a different area.
- Each string file has the same keys in all six languages: `values` (English), `values-es`, `values-fr`,
  `values-de`, `values-pt-rBR`, and `values-nl`. `:core:resources:localizationCheck` must pass.
- Only Compose reads the string files, except `strings_app.xml` and `strings_probe.xml`. Compose does not remove a
  backslash before a quote. Thus write a plain apostrophe (`Can't set RPM`) in each other file. Never write `\'`.

## Tests

- Put unit tests in `commonTest` (or `jvmTest` for file fixtures and the database). Use `kotlin-test` and
  `kotlinx-coroutines-test` with virtual time.

## Gradle

Many agents build at the same time on one computer. Use these flags for each Gradle call, so that the
builds do not use all the memory:

```bash
./gradlew --max-workers=4 -Dorg.gradle.jvmargs="-Xmx6g -XX:+UseParallelGC" \
  -Pkotlin.compiler.execution.strategy=in-process <tasks>
```

- Run only the tasks of your modules, for example `:core:protocol:jvmTest` or `:core:protocol:allTests`.
- For `:core:ui` and `:validation`, run the domain task of your area (`:core:ui:jvmTestAdapter`, `:core:ui:jvmTestFunctions`, `:core:ui:jvmTestGraphics`, `:core:ui:jvmTestMcp`, `:core:ui:jvmTestReadout`, `:core:ui:jvmTestSettings`, `:core:ui:jvmTestTabs`, `:core:ui:jvmTestScreenshots`; `:validation:testFunctions`, `:validation:testProtocol`, `:validation:testReadout`, `:validation:testStore`, `:validation:testMcp`, `:validation:testProject`, `:validation:testUi`, `:validation:testUiScreens`, `:validation:testUiScreenshots`).
- The root task `validateJvm` (also the default task) runs all `jvmTest` tasks, `:validation:test`, `:core:resources:localizationCheck`, and `:core:store:verifySqlDelightMigration`. The root task `validateAndroid` runs the Android assemblies, test compile checks, and `lint`.
- Before you push a branch, run `scripts/validate.sh`. The script runs the release script tests, the `composite` tests, and `validateJvm`. It uses the build cache, so only the tests of a changed module run again. `--rerun` runs each test task again.
- After a change under `composite/`, run `./gradlew -p composite clean test publish` and commit `composite/m2`.
- Do not run `./gradlew clean` on the full project of a different worktree.

## Android emulator

- The coordinator owns the AVD `Pixel_Tablet`. Do not start it.
- If you must run an instrumented test, make your own AVD with a unique name (for example `sd_<agent>`),
  start it with `-no-window -no-audio -port <your port>`, and set `ANDROID_SERIAL=emulator-<your port>`.
  Stop your emulator when you finish.
- A physical tablet can be attached to this computer. Never install on it and never run a command on it.
  Always give `adb` the serial of your own emulator.
