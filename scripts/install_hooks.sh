#!/usr/bin/env bash
# Turns on the git hooks of .githooks for this clone and all its worktrees.
# Run it one time after the clone: scripts/install_hooks.sh

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
git config core.hooksPath .githooks
echo "The git hooks of .githooks are on (core.hooksPath)."
