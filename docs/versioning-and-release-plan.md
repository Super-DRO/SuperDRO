# Version and release

This document is the reference for the app version and for the release workflows. Code comments
cite its section numbers. If you change a section number, change the comments too.

## 1. Goals

The version system and the release system have these five properties:

1. The version comes from the calendar. Nobody edits a version by hand. One version goes to each
   target (section 3).
2. A GitHub Action cuts a release. A developer does not edit a file to cut a release (section 5).
3. The git tag is the single source of truth. The build workflow gets the version from the tag,
   and it builds and publishes each target (sections 5 and 6).
4. A development build and a release build are always different. A development build has the
   `-dev` marker. Only the build workflow, on a clean checkout of a tag, publishes a clean release
   version (section 4).
5. A release contains only code on `main`. Both release workflows refuse a commit that is not on
   `main` (section 5).

## 2. Files

| File | Function |
|---|---|
| `composite/src/main/kotlin/org/superdro/composite/util/Version.kt` | `resolveProjectVersion()`, `projectVersionCode()`, `packagingVersion()` |
| `composite/src/test/kotlin/org/superdro/composite/util/VersionTest.kt` | The tests of the version rules of the build |
| `build.gradle.kts` | Sets the version on all projects |
| `app/android/build.gradle.kts`, `app/adapter-probe/build.gradle.kts` | `versionName`, `versionCode`, the release `signingConfig` |
| `app/desktop/build.gradle.kts` | `packageVersion` of the desktop installers |
| `core/app-version/build.gradle.kts` | Generates `AppVersion` for the common code |
| `scripts/cut_version.py` | Calculates the next version and makes the tag |
| `scripts/tests/test_cut_version.py` | The tests of `cut_version.py` |
| `.github/workflows/release.yml` | Cuts a release: makes the tag and starts the build |
| `.github/workflows/build-release.yml` | Builds the tag and publishes the release |
| `.github/workflows/validation.yml` | The development path: pull requests and pushes to `main` |
| `signing/upload.keystore.gpg` | The encrypted upload keystore (section 7). It is not in the repository yet. |

## 3. Version form

The version has the form `<year>.<week>.<count>`:

- `year` is the last two digits of the ISO-8601 week-year.
- `week` is the ISO-8601 week, from 1 to 53.
- `count` is the number of the release in that week. The first release in a week has the count 1.

The first release in ISO week 42 of 2026 is `26.42.1`. The second release in that week is
`26.42.2`. The git tag adds a `v`: `v26.42.1`.

One version gives all these values:

| Target | Field | Value for `26.42.1` |
|---|---|---|
| Git tag, GitHub Release | tag name, release name | `v26.42.1`, `26.42.1` |
| Android (`app:android`, `app:adapter-probe`) | `versionName` | `26.42.1` |
| Android (`app:android`, `app:adapter-probe`) | `versionCode` | `2642001` |
| Desktop installers | `packageVersion` | `26.42.1` |
| Common code (`core:app-version`) | `AppVersion.NAME`, `AppVersion.CODE` | `26.42.1`, `2642001` |

**The year has two digits.** The MSI tool of jpackage refuses a major part above 255. The DMG
tool and the MSI tool accept only numbers (`MAJOR.MINOR.PATCH`). A four digit year (`2026.42.1`)
cannot make a Windows package. jpackage examines the version when Gradle configures the project.
Thus a bad version stops `./gradlew help` and each CI job, not only the package tasks.

**Integer code.** Android needs an integer that always increases:

```
code = (year mod 100) × 100000 + week × 1000 + count
```

- `26.42.1` gives `26·100000 + 42·1000 + 1 = 2642001`.
- The year term is the largest term. Thus week 1 of a new year has a higher code than week 52 of
  the old year: `27.1.1` gives `2701001`, which is more than `26.52.9`.
- The week (53 or less) and the count (999 or less) fit in the `×100000` step. The Google Play
  limit is `2100000000`, so the codes stay in range.
- The code goes back to a low value in 2100, because `00.x.y` is less than `99.x.y`.
- The code ignores the `-dev` part. A development build before the first tag (`0.0.0-dev`) gets
  the code 1, because Android needs a `versionCode` of 1 or more.

Two functions calculate this code: `projectVersionCode()` in `Version.kt` and `version_code()` in
`scripts/cut_version.py`. Keep them the same. Their tests use the same values.

**Week number.** Use the ISO-8601 week with the ISO week-year (`date +%G-%V`, Python
`date.isocalendar()`). Do not use `%Y` with `%V`. The two years are different near the new year,
and the mix gives a lower version after a higher one. For example, 2025-12-29 is in week 1 of the
ISO week-year 2026. With `%Y`, that day gives `25.1` after `25.52`.

**Count from the tags.** The count is the highest count of the tags `v<year>.<week>.*`, plus 1.
When the week has no tag, the count is 1. `cut_version.py` ignores a tag that does not have the
form `v<int>.<int>.<int>`. The repository stores no counter.

## 4. Version source in the build

The repository has no version file. One resolver calculates the version, and each module uses
it. Thus all targets always show the same version.

`resolveProjectVersion()` in `Version.kt` runs this command one time, through a Gradle
`ValueSource`:

```
git describe --tags --match "v[0-9]*.[0-9]*.[0-9]*" --always --dirty
```

The `ValueSource` keeps the configuration cache valid. `--match` makes git use only release tags.
A tag of a different form on `main` does not change the version. The root `build.gradle.kts` sets
the result on `allprojects`. `parseGitDescribe()` changes the `git describe` output as follows:

| `git describe` output | Version | Build type |
|---|---|---|
| `v26.42.1` (on a tag, clean tree) | `26.42.1` | Release |
| `v26.42.1-7-gabc123` | `26.42.1-dev+7-gabc123` | Development |
| `v26.42.1-dirty` | `26.42.1-dev+dirty` | Development |
| `abc1234` (no release tag) | `0.0.0-dev+gabc1234` | Development |
| No output (no git) | `0.0.0-dev` | Development |

A clean release version has no `-dev` marker (goal 4). The part after `+` is SemVer build
metadata. The resolver changes a `+` in that part to a `.`.

**Where the version goes:**

- **Android.** `versionName` is the full version, with the marker. `versionCode` is
  `projectVersionCode(version)` (section 3). The SuperDRO app and the adapter probe have the same
  values.
- **Desktop.** `packageVersion` is `packagingVersion(project.version)`. This function removes all
  text from the first `-`, so `26.42.1-dev+7-gabc123` becomes `26.42.1`. The function is
  necessary, because jpackage refuses a suffix when Gradle configures the project. The DMG and MSI
  tools also refuse the major part 0. Thus a tree with no release tag packages as `1.0.0`.
- **Common code.** `core:app-version` generates `AppVersion` with `NAME` and `CODE`. `NAME` does
  not include the build metadata after `+`, so it stays the same between commits
  (`26.43.1-dev`). The `-dev` marker still shows a development build. To include the metadata,
  build with `-Psuperdro.stampBuildMetadata=true`. The About screen shows `NAME` and `CODE`. The
  export files write `NAME` in their header. `VersionWatcher` compares `NAME` with the version of
  the last start, and it shows the changes after an update.

**Provenance.** A local build on a clean checkout of a tag also gets the clean version. Thus the
version alone does not show which workflow made an artifact. A release is an artifact that the
build workflow published from a clean checkout of a tag.

The root build gets `composite` as a published jar from `composite/m2`. After a change to
`Version.kt`, publish that jar again. `CLAUDE.md` gives the procedure.

## 5. Release flow

A release uses two workflows. `release.yml` calculates the version and pushes the tag.
`build-release.yml` builds that tag and publishes it.

```
maintainer           release.yml (workflow_dispatch)        build-release.yml (workflow_dispatch)
──────────           ───────────────────────────────        ─────────────────────────────────────
Run "Release" ─────▶ refuse a ref that is not on main
 (Actions tab)       run the scripts/tests unit tests
                     cut_version.py --tag --ref HEAD
                     ├─ read the tags → next v26.42.N
                     ├─ git tag -a v26.42.N
                     ├─ git push origin v26.42.N (GITHUB_TOKEN: no push event)
                     └─ gh workflow run build-release.yml --ref v26.42.N ──▶ preflight
                                                                            ├─ refuse a tag not on main
                                                                            ├─ version and code from the tag
                                                                            ├─ find the secrets (section 8)
                                                                            ├─ desktop ×3, android
                                                                            ├─ create-release (GitHub Release)
                                                                            └─ publish-play (gated, section 6.2)
```

**`release.yml`:**

- The trigger is `workflow_dispatch`. The optional `ref` input names the commit to release.
  Without the input, the workflow uses its own ref.
- The job stops when the commit is not `main` or an ancestor of `main`.
- The `release-cut` concurrency group prevents two cuts at the same time. Two cuts at the same time
  can calculate the same count.
- The job pushes the tag with the usual `GITHUB_TOKEN`. GitHub does not send a `push` event for a
  push with that token. Thus the job then starts the build explicitly with `gh workflow run`.
- The job publishes nothing and reads no secret of a signature.

**`build-release.yml`:**

- `workflow_dispatch` is the usual trigger. The optional `tag` input names the tag. Without the
  input, the workflow uses the ref of the run.
- `push: tags: ['v*']` is only a safety net, for a tag that somebody pushed with a personal access
  token (PAT).
- The preflight job stops when the ref is not `v<int>.<int>.<int>`, or when the tag is not on
  `main`.
- The concurrency group is one group for each tag. A new run does not cancel a run in progress.
- `workflow_dispatch` works only for a workflow file on the default branch (`main`).

Do not push a tag with a PAT while `release.yml` also starts the build. That causes a `push` event
and a `workflow_dispatch` event, and the tag builds two times. If a tag does not build, run
`build-release.yml` from the Actions tab with the `tag` input. If only one job failed, use
"Re-run failed jobs".

To see the next version with no change, run `python3 scripts/cut_version.py --dry-run` locally.
`--print` writes only the next version.

To remove a bad release, delete the GitHub Release and then the tag. Do not use the version of a
deleted tag again for a different commit: the count of the next cut goes after the highest count
that exists.

`validation.yml` (pull requests and pushes to `main`) is the development path. It never makes a
tag and never publishes. It runs the tests of `scripts/tests` and of `composite`, which guard the
version rules.

## 6. Build and publish of each target

Each build job checks out the tag with `fetch-depth: 0`, because `git describe` needs the tag
(section 4). The jobs are:

| Job | Needs | Gate | Output |
|---|---|---|---|
| `preflight` | — | — | Tag, version, code, and one flag for each secret group (section 8) |
| `desktop` (Linux, Windows, macOS) | `preflight` | — | `.deb`, `.msi`, `.dmg` (section 6.1) |
| `android` | `preflight` | — | AAB and APK of SuperDRO, APK of the adapter probe (section 6.2) |
| `create-release` | `preflight`, `desktop`, `android` | — | The GitHub Release |
| `publish-play` | `preflight`, `android` | `has_play` and `has_android_signing` | Google Play internal track |

`create-release` downloads all build artifacts into one directory. If two files have the same
name, the job stops, because one file would replace the other. The job then makes the Release with
`softprops/action-gh-release`: `draft: false`, `prerelease: false`, `make_latest: true`,
`generate_release_notes: true` and `fail_on_unmatched_files: true`.

The Gradle caches: only a run of `validation.yml` on `main` writes them. A release run is on a
tag, so `setup-gradle` gets `cache-read-only: true`.

### 6.1 Desktop

Three runners (`ubuntu-latest`, `windows-latest`, `macos-latest`) each run
`:app:desktop:packageDistributionForCurrentOS`. jpackage makes only the installer of its own
system:

| Runner | Installer | Example name |
|---|---|---|
| Linux | `.deb` | `superdro_26.42.1_amd64.deb` |
| Windows | `.msi` | `SuperDRO-26.42.1.msi` |
| macOS | `.dmg` | `SuperDRO-26.42.1.dmg` |

Use `packageDistributionForCurrentOS`. Do not use `packageReleaseDistributionForCurrentOS`. That
task runs ProGuard, which has no configuration here and removes classes that Kodein and
kotlinx.serialization find by reflection.

Gradle configures all projects, also the Android projects, for a desktop task. Thus each desktop
runner installs the Android SDK.

The desktop app has no updater. The `macos-latest` runner has an Apple silicon CPU, so the `.dmg`
is for Apple silicon only. The installers are not signed (section 7).

### 6.2 Android

- The `android` job runs `:app:android:bundleRelease`, `:app:android:assembleRelease`, and
  `:app:adapter-probe:assembleRelease`. The version comes from section 4.
- The release builds are signed when the upload keystore and its secrets exist (section 7).
  Without them, the builds are unsigned, and Android cannot install an unsigned APK.
- The job reads `versionName` and `versionCode` of each APK with `aapt2 dump badging`. It stops
  when a value is not the value of the tag.
- The job gives each file a fixed name: `superdro-<version>.aab`, `superdro-<version>.apk`, and
  `superdro-adapter-probe-<version>.apk`. An unsigned file gets `-unsigned` after the version.
- The job attaches the files to the GitHub Release. The APKs are for a sideload on the tablet.
- `publish-play` uploads `superdro-<version>.aab` to the `internal` track of the Play app
  `org.superdro` with `r0adkll/upload-google-play`. It runs only when `has_play` and
  `has_android_signing` are both true. The adapter probe does not go to Play.

## 7. Signatures

Each step that signs runs only when its secrets exist (section 8). Without the secrets, the artifact
is unsigned. Local, pull request, and validation builds never sign.

- **Android.** The release `signingConfig` of `app:android` and `app:adapter-probe` exists only
  when `ANDROID_KEYSTORE_FILE` is set. The upload keystore is in the repository as the
  GPG-encrypted file `signing/upload.keystore.gpg` (symmetric, AES256). Before the build, the
  `android` job decrypts it with `ANDROID_KEYSTORE_GPG_PASSPHRASE` and sets
  `ANDROID_KEYSTORE_FILE`. Both apps use the same upload key. When the app is on Google Play, Play
  App Signing holds the app key.
- **The upload keystore.** Use a PKCS12 keystore with the alias `upload` and an RSA 4096 key.
  PKCS12 has one password for the store and the key, so `ANDROID_KEYSTORE_PASSWORD` and
  `ANDROID_KEY_PASSWORD` have the same value. `.gitignore` keeps all other files of `signing/`
  out of Git, so the decrypted keystore and its passwords can stay there on a local disk.
- **Desktop.** The `.deb`, `.msi`, and `.dmg` are not signed. Windows shows a SmartScreen warning before the installation.
  On macOS, the user must right-click the app and select Open to get past Gatekeeper.

To make the upload keystore (one time):

```bash
mkdir -p signing
keytool -genkeypair -storetype PKCS12 -keystore signing/upload.keystore \
  -alias upload -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=SuperDRO"
gpg --symmetric --cipher-algo AES256 \
  --output signing/upload.keystore.gpg signing/upload.keystore
git add signing/upload.keystore.gpg
```

Keep a copy of `signing/upload.keystore` and its passwords in a safe location outside the
repository. If you lose the upload key, an installed app cannot get an update with a new key.

**The `release` environment.** Each job that reads a secret for a signature or a publication declares
`environment: release`. A repository secret is available to each workflow on each branch. An
environment secret is available only to a job in that environment. GitHub makes the environment
at the first run when it does not exist. Before the repository becomes public, give the `release`
environment required reviewers, so that a maintainer approves each release job.

## 8. Secrets and settings

Each group of secrets has its own gate, so each group enables only its own part of the release.
Keep these secrets in the `release` environment, not in the repository secrets.

| Group | Secrets | Gate (preflight output) | Without the secrets |
|---|---|---|---|
| Android signature | `ANDROID_KEYSTORE_GPG_PASSPHRASE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | `has_android_signing` (also needs `signing/upload.keystore.gpg` in the tag) | Unsigned AAB and APKs, no Play upload |
| Play upload | `PLAY_SERVICE_ACCOUNT_JSON` | `has_play` | No Play upload |

The value of `ANDROID_KEY_ALIAS` is `upload`. `ANDROID_KEYSTORE_PASSWORD` and
`ANDROID_KEY_PASSWORD` have the same value (section 7).

Repository settings:

- **Actions > General > Workflow permissions.** The workflows set their own scopes. The
  organization or the repository must permit `contents: write` and `actions: write` for the
  `GITHUB_TOKEN`. "Read and write permissions" permits them.
- **Rules for tags.** A tag ruleset that blocks the creation of `v*` tags also blocks
  `release.yml`. If you add such a ruleset, let GitHub Actions bypass it.
- **Environments > release.** Optional now. Add the secrets there, and add required reviewers
  before the repository becomes public.

## 9. Cut a release

1. Merge the changes for the release into `main`. Make sure that `validation.yml` passes on
   `main`.
2. Optional: run `python3 scripts/cut_version.py --dry-run` to see the next version.
3. In GitHub, open Actions > "Release (cut tag)" > "Run workflow". Select the branch `main`. Leave
   the `ref` input empty.
4. The workflow makes the tag and starts "Build Release". Open that run and wait for it.
5. Examine the new GitHub Release. It has the `.deb`, `.msi`, `.dmg`, the AAB, and the two APKs.
