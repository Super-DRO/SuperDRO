#!/usr/bin/env bash
# Runs the CI validation checks locally with memory limits.
# Usage:
#   scripts/validate.sh            # release script tests, composite tests, and validateJvm
#   scripts/validate.sh --android  # also runs validateAndroid
#   scripts/validate.sh --rerun    # runs each test task again, also with a cached result
#   scripts/validate.sh --ci       # the gate before a push: see "CI mode" below
#   scripts/validate.sh --ci --repeat 5   # CI mode with 5 slow runs of the changed modules
#
# The test tasks declare their inputs, so a cached test result is as valid as a new run. Thus the
# default run uses the build cache, and only the tests of a changed module run again.
#
# CI mode (--ci) is the gate before each push and each pull request update. Most CI failures of
# this project came from a difference between CI and the local computer, not from a defect that
# the local checks could not find:
#   - CI has no display. A test that opened an AWT window passed locally and failed in CI.
#   - CI uses the locale C.UTF-8 and the time zone UTC.
#   - The CI runner has few CPUs. Tests with a race passed locally and failed in CI at random.
#   - A cached local test result hides a test that fails only at random.
# Thus CI mode:
#   1. runs Gradle with no display, LANG=C.UTF-8, and TZ=UTC, in a new daemon (--no-daemon),
#   2. runs validateJvm and validateAndroid with no cached test result (-PrerunTests=true),
#   3. runs the JVM tests of each module that the branch changes again --repeat times (default
#      3) on 2 CPUs (scripts/changed_test_tasks.py).
# CI mode does not find a change of the CI runner image. scripts/wait_for_ci.sh finds it after
# the push.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# The stamp of a passed CI mode run: the validated commit. .githooks/pre-push refuses to push a
# different commit. Each worktree has its own git directory, thus its own stamp.
STAMP_FILE="$(git rev-parse --git-dir)/superdro-ci-validated"

RUN_ANDROID=false
RERUN_FLAG=""
CI_MODE=false
REPEAT=3

usage() {
    echo "Usage: scripts/validate.sh [--android] [--rerun] [--ci [--repeat N]]" >&2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android)
            RUN_ANDROID=true
            ;;
        --rerun)
            RERUN_FLAG="-PrerunTests=true"
            ;;
        --ci)
            CI_MODE=true
            RUN_ANDROID=true
            RERUN_FLAG="-PrerunTests=true"
            ;;
        --repeat)
            shift
            if [[ $# -eq 0 || ! "$1" =~ ^[0-9]+$ ]]; then
                usage
                exit 2
            fi
            REPEAT="$1"
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            exit 2
            ;;
    esac
    shift
done

# The environment of the CI runner. A Gradle daemon keeps the time zone and the locale of its
# start, so CI mode uses a new daemon for each Gradle call.
CI_ENV=()
DAEMON_ARGS=()
if [[ "$CI_MODE" == "true" ]]; then
    CI_ENV=(env -u DISPLAY -u WAYLAND_DISPLAY LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC CI=true)
    DAEMON_ARGS=(--no-daemon)
fi

echo "==> Validate (release scripts)"
python3 -m unittest discover -s scripts/tests

echo "==> Test build logic (composite)"
"${CI_ENV[@]}" ./gradlew -p composite test --no-daemon --stacktrace -Dorg.gradle.console=plain

TASKS=(validateJvm)
if [[ "$RUN_ANDROID" == "true" ]]; then
    TASKS+=(validateAndroid)
fi

GRADLE_ARGS=(
    --max-workers=4
    -Dorg.gradle.jvmargs="-Xmx6g -XX:+UseParallelGC"
    -Pkotlin.compiler.execution.strategy=in-process
    --continue
    --stacktrace
    -Dorg.gradle.console=plain
)
if [[ -n "$RERUN_FLAG" ]]; then
    GRADLE_ARGS+=("$RERUN_FLAG")
fi

echo "==> Validate (${TASKS[*]})"
"${CI_ENV[@]}" ./gradlew "${DAEMON_ARGS[@]}" "${GRADLE_ARGS[@]}" "${TASKS[@]}"

if [[ "$CI_MODE" != "true" ]]; then
    exit 0
fi

# The stamp is valid only for a clean work tree: then the validated files are the files of HEAD.
# Remove an old stamp now, so that a failed run leaves no stamp.
rm -f "$STAMP_FILE"
VALIDATED_SHA=""
if [[ -z "$(git status --porcelain)" ]]; then
    VALIDATED_SHA="$(git rev-parse HEAD)"
fi
write_stamp() {
    if [[ -n "$VALIDATED_SHA" && "$(git rev-parse HEAD)" == "$VALIDATED_SHA" && -z "$(git status --porcelain)" ]]; then
        echo "$VALIDATED_SHA" > "$STAMP_FILE"
        echo "==> CI mode passed for $VALIDATED_SHA. git push can push this commit."
    else
        echo "WARNING: the work tree has changes, or HEAD changed. No stamp: commit, then run CI mode again." >&2
    fi
}

# Slow runs of the changed modules: 2 CPUs and 2 workers, as on a busy CI runner. A test with a
# race then fails here and not at random in CI.
mapfile -t CHANGED < <(python3 scripts/changed_test_tasks.py origin/main)
if [[ ${#CHANGED[@]} -eq 0 ]]; then
    echo "==> Slow runs: the branch changes no module with JVM tests"
    write_stamp
    exit 0
fi
if [[ " ${CHANGED[*]} " == *" validateJvm "* ]]; then
    echo "==> Slow runs: skipped. The branch changes the build logic, and the full run above ran all tests."
    write_stamp
    exit 0
fi

LIMIT=()
if command -v taskset >/dev/null 2>&1; then
    CPUS="$(python3 -c 'import os, random; print(",".join(map(str, sorted(random.sample(sorted(os.sched_getaffinity(0)), 2)))))')"
    LIMIT=(taskset -c "$CPUS")
else
    echo "WARNING: taskset is not available. The slow runs use all CPUs." >&2
fi

SLOW_ARGS=(
    --max-workers=2
    -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC"
    -Pkotlin.compiler.execution.strategy=in-process
    -PrerunTests=true
    --continue
    --stacktrace
    -Dorg.gradle.console=plain
)
for run in $(seq 1 "$REPEAT"); do
    echo "==> Slow run $run of $REPEAT (${LIMIT[*]:-all CPUs}): ${CHANGED[*]}"
    "${CI_ENV[@]}" "${LIMIT[@]}" ./gradlew --no-daemon "${SLOW_ARGS[@]}" "${CHANGED[@]}"
done
write_stamp
