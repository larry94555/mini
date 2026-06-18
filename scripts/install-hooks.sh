#!/usr/bin/env sh
# Point git at the repo's hooks (one time). Git does not run repo hooks unless core.hooksPath is set.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT"
chmod +x .githooks/pre-commit .githooks/check-scripts.sh 2>/dev/null || true
git config core.hooksPath .githooks
echo "Installed git hooks (core.hooksPath=.githooks)."
