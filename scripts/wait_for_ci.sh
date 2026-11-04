#!/usr/bin/env bash
# Waits for the CI checks of a pull request and stops with an error if a check fails.
# Usage:
#   scripts/wait_for_ci.sh        # the pull request of the current branch
#   scripts/wait_for_ci.sh 69     # pull request 69
#
# A work item is not done until this script passes (CLAUDE.md, "Definition of done"). The
# script first makes sure that the pull request has the local HEAD commit, so that the checks
# are the checks of the pushed work. When a check fails, the script shows the failed Gradle
# tasks, the failed tests, and the "What went wrong" texts of the failed jobs.

set -euo pipefail

PR="${1:-}"
if [[ -z "$PR" ]]; then
    PR="$(gh pr view --json number --jq .number)"
fi

HEAD_SHA="$(gh pr view "$PR" --json headRefOid --jq .headRefOid)"
LOCAL_SHA="$(git rev-parse HEAD)"
if [[ "$HEAD_SHA" != "$LOCAL_SHA" ]]; then
    echo "ERROR: pull request $PR has the commit $HEAD_SHA, but the local HEAD is $LOCAL_SHA." >&2
    echo "Push the branch first." >&2
    exit 1
fi

# GitHub can need some seconds after a push before it shows the checks of the new commit.
for _ in $(seq 1 30); do
    if [[ "$(gh pr checks "$PR" --json name --jq length 2>/dev/null || echo 0)" -gt 0 ]]; then
        break
    fi
    sleep 10
done

echo "==> The CI checks of pull request $PR ($HEAD_SHA)"
if gh pr checks "$PR" --watch --interval 30; then
    echo "==> All CI checks of pull request $PR passed."
    exit 0
fi

echo
echo "==> The failed CI checks of pull request $PR"
gh pr checks "$PR" --json name,state,link \
    --jq '.[] | select(.state == "FAILURE" or .state == "CANCELLED" or .state == "TIMED_OUT") | "\(.name)\t\(.link)"' |
    while IFS=$'\t' read -r name link; do
        echo
        echo "--- $name: $link"
        job="${link##*/job/}"
        run="${link%%/job/*}"
        run="${run##*/runs/}"
        if [[ "$job" =~ ^[0-9]+$ && "$run" =~ ^[0-9]+$ ]]; then
            gh run view "$run" --job "$job" --log-failed 2>/dev/null |
                cut -f3- |
                sed -E 's/^[0-9TZ:.-]+ //' |
                grep -E 'What went wrong|^Execution failed|FAILED$|Caused by:|^> |AssertionError|::error' |
                grep -vE '^\s+at ' |
                awk '!seen[$0]++' |
                head -40 || true
        fi
    done
exit 1
