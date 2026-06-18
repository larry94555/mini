#!/usr/bin/env sh
# One-shot: mark the wrapper and POSIX scripts executable in git (mode 100755), so ./mvnw and ./run.sh
# work without chmod and the CI hygiene guard passes. Run once from the repo, then commit.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT"
git update-index --chmod=+x \
  mvnw run.sh ask.sh chat.sh plan.sh stream.sh rewind.sh interrupt.sh runs.sh steer.sh eval.sh \
  scripts/common.sh scripts/install-hooks.sh scripts/pin-maven-checksum.sh scripts/git-mark-exec.sh \
  .githooks/pre-commit .githooks/check-scripts.sh
echo "Marked scripts executable in git (100755). Now commit: git commit -m 'Mark scripts executable'"
