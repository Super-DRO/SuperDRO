#!/usr/bin/env bash
# Runs the CI validation checks locally with memory limits.
# Usage:
#   scripts/validate.sh            # release script tests, composite tests, and validateJvm
#   scripts/validate.sh --android  # also runs validateAndroid
#   scripts/validate.sh --rerun    # runs each test task again, also with a cached result
#
# The test tasks declare their inputs, so a cached test result is as valid as a new run. Thus the
# default run uses the build cache, and only the tests of a changed module run again.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_ANDROID=false
RERUN_FLAG=""

for arg in "$@"; do
    case "$arg" in
        --android)
            RUN_ANDROID=true
            ;;
        --rerun)
            RERUN_FLAG="-PrerunTests=true"
            ;;
        *)
            echo "Unknown option: $arg" >&2
            echo "Usage: scripts/validate.sh [--android] [--rerun]" >&2
            exit 2
            ;;
    esac
done

echo "==> Validate (release scripts)"
python3 -m unittest discover -s scripts/tests

echo "==> Test build logic (composite)"
./gradlew -p composite test --no-daemon --stacktrace -Dorg.gradle.console=plain

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
./gradlew "${GRADLE_ARGS[@]}" "${TASKS[@]}"
