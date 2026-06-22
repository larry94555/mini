#!/usr/bin/env sh
# Verify script hygiene against the git index: required scripts are executable (mode 100755) and
# LF-only files contain no CR. Used by the pre-commit hook and by CI. Exit non-zero on any problem.
set -eu

EXEC_SCRIPTS="mvnw run.sh ask.sh chat.sh plan.sh stream.sh rewind.sh interrupt.sh runs.sh steer.sh eval.sh scripts/common.sh scripts/install-hooks.sh scripts/pin-maven-checksum.sh scripts/git-mark-exec.sh"
fail=0

for f in $EXEC_SCRIPTS; do
  mode="$(git ls-files --stage -- "$f" 2>/dev/null | awk '{print $1}')"
  [ -n "$mode" ] || continue   # not tracked yet -> skip
  if [ "$mode" != "100755" ]; then
    echo "  [exec] $f is mode $mode (want 100755) -> git update-index --chmod=+x $f"
    fail=1
  fi
done

# Every tracked git hook must be executable (100755) or git silently won't run it after a fresh clone +
# install-hooks -- the bit is easily lost on an archive import. Enforce it for all of .githooks/.
for f in $(git ls-files .githooks 2>/dev/null); do
  mode="$(git ls-files --stage -- "$f" 2>/dev/null | awk '{print $1}')"
  [ -n "$mode" ] || continue
  if [ "$mode" != "100755" ]; then
    echo "  [exec] $f is mode $mode (want 100755) -> git update-index --chmod=+x $f"
    fail=1
  fi
done

CR="$(printf '\r')"
for f in $(git ls-files '*.sh' 'mvnw' 2>/dev/null); do
  if git show ":$f" 2>/dev/null | grep -q "$CR"; then
    echo "  [eol]  $f contains CRLF; must be LF"
    fail=1
  fi
done

if [ "$fail" = 0 ]; then echo "script hygiene: OK"; fi
exit $fail
