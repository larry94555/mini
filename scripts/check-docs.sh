#!/usr/bin/env bash
# check-docs.sh — fail if the teaching docs reference a test class, Java source file, or TESTING.md case
# number that does not exist in the repo. Keeps the cross-referenced docs (README.md, docs/*.md) from
# silently rotting when something is renamed, moved, or deleted.
#
# Scope: the LIVING docs only — README.md and docs/*.md EXCEPT docs/HISTORY.md, which is an archive that
# intentionally names files/changes that were removed. Editor backups and hidden files are ignored.
# Dependency-free: bash + grep + find (no node, no maven).
#
# Usage:
#   scripts/check-docs.sh               # check; exit 1 on any broken reference
#   WARN_ONLY=1 scripts/check-docs.sh   # report but always exit 0 (non-blocking mode)
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT"

WARN_ONLY="${WARN_ONLY:-0}"

# Living docs: README.md + docs/*.md, minus the history archive and editor backups.
docs=()
[ -f README.md ] && docs+=("README.md")
while IFS= read -r f; do docs+=("$f"); done < <(
  find docs -maxdepth 1 -type f -name '*.md' ! -name 'HISTORY.md' 2>/dev/null | sort
)
if [ "${#docs[@]}" -eq 0 ]; then
  echo "No living docs found to check."; exit 0
fi

broken="$(mktemp)"
trap 'rm -f "$broken"' EXIT

# 1) Test-class references: `FooTest` or `FooTest.method` -> must exist under src/test as FooTest.java
grep -rhoE '`[A-Z][A-Za-z0-9]+Test' "${docs[@]}" 2>/dev/null | sed -e 's/`//' -e 's/\..*//' | sort -u \
  | while IFS= read -r t; do
      [ -z "$t" ] && continue
      find src/test -name "$t.java" 2>/dev/null | grep -q . \
        || echo "test class referenced but not found: $t (expected src/test/.../$t.java)" >> "$broken"
    done

# 2) Java-source references: `Bar.java` -> must exist somewhere under src/
grep -rhoE '`[A-Za-z0-9_]+\.java`' "${docs[@]}" 2>/dev/null | sed 's/`//g' | sort -u \
  | while IFS= read -r j; do
      [ -z "$j" ] && continue
      find src -name "$j" 2>/dev/null | grep -q . \
        || echo "Java file referenced but not found: $j" >> "$broken"
    done

# 3) TESTING.md case-number references: `cases 549-568`, `case 572` -> each number is a '## N.' heading
grep -rhoE 'cases? [0-9]+(-[0-9]+)?' "${docs[@]}" 2>/dev/null | grep -oE '[0-9]+' | sort -nu \
  | while IFS= read -r n; do
      [ -z "$n" ] && continue
      grep -qE "^## $n\." TESTING.md 2>/dev/null \
        || echo "TESTING.md case referenced but no '## $n.' heading exists: $n" >> "$broken"
    done

count="$(wc -l < "$broken" | tr -d ' ')"
scanned="${#docs[@]}"

if [ "$count" -eq 0 ]; then
  echo "OK: all doc references resolve ($scanned living docs scanned)."
  exit 0
fi

echo "Found $count broken doc reference(s) across $scanned living docs:"
sed 's/^/  [BROKEN] /' "$broken"
if [ "$WARN_ONLY" = "1" ]; then
  echo "(WARN_ONLY=1 — not failing the build.)"
  exit 0
fi
exit 1
