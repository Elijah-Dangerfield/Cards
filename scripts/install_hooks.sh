#!/usr/bin/env bash
# Wires .githooks/ as the Git hooks directory for this clone.
# Idempotent — safe to re-run. The Gradle `verifyGitHooks` task runs this
# check on every build, so forgetting to install fails early.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/*
echo "Git hooks installed — .githooks/ is now active."
