#!/usr/bin/env bash
# Makes the repository secret GRADLE_ENCRYPTION_KEY for the setup-gradle action.
#
# The action saves the Gradle configuration cache in the GitHub Actions cache only when the
# workflow gives it this key (a base64 AES key). Without the key, each CI job calculates the task
# graph again, which takes 25 to 40 s. The workflows read the key from the secret
# GRADLE_ENCRYPTION_KEY (see .github/workflows/validation.yml).
#
# Usage:
#   scripts/set_gradle_encryption_key.sh          # the repository of the current checkout
#   scripts/set_gradle_encryption_key.sh OWNER/REPO
#
# The script needs the GitHub CLI (gh) with a login that can write the secrets of the repository.
# A new key makes the saved configuration cache entries unreadable. The next run on main then saves
# new entries. Thus you can run the script again at any time.

set -euo pipefail

REPO_ARGS=()
if [[ $# -gt 1 ]]; then
    echo "Usage: scripts/set_gradle_encryption_key.sh [OWNER/REPO]" >&2
    exit 2
fi
if [[ $# -eq 1 ]]; then
    REPO_ARGS=(--repo "$1")
fi

if ! command -v gh > /dev/null; then
    echo "The GitHub CLI (gh) is not installed." >&2
    exit 1
fi
if ! command -v openssl > /dev/null; then
    echo "openssl is not installed." >&2
    exit 1
fi

# gh reads the key from stdin, so the key is not in the process list and not in the shell history.
openssl rand -base64 16 | gh secret set GRADLE_ENCRYPTION_KEY "${REPO_ARGS[@]}"
echo "The secret GRADLE_ENCRYPTION_KEY is set. The next run on main saves the configuration cache."
