#!/usr/bin/env sh
# check-docs.sh — fail if the teaching docs reference a test class, Java source file, or TESTING.md case
# number that does not exist in the repo. Keeps the cross-referenced docs (README.md, docs/*.md) from
# silently rotting when something is renamed, moved, or deleted.
#
# Scope: the LIVING docs only — README.md and docs/*.md EXCEPT docs/HISTORY.md, which is an archive that
# intentionally names files/changes that were removed. Editor backups and hidden files are ignored.
# Dependency-free and POSIX sh (no bash arrays/process substitution): sh + grep + find.
#
# Usage:
#   scripts/check-docs.sh               # check; exit 1 on any broken reference
#   WARN_ONLY=1 scripts/check-docs.sh   # report but always exit 0 (non-blocking mode)
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT"

WARN_ONLY="${WARN_ONLY:-0}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
doclist="$work/docs"   # living docs, one path per line
broken="$work/broken"  # broken references, one message per line
: > "$doclist"
: > "$broken"

# Living docs: README.md + docs/*.md, minus the history archive.
[ -f README.md ] && printf '%s\n' "README.md" >> "$doclist"
find docs -maxdepth 1 -type f -name '*.md' ! -name 'HISTORY.md' 2>/dev/null | sort >> "$doclist"

if [ ! -s "$doclist" ]; then
  echo "No living docs found to check."
  exit 0
fi

# grep across exactly the living docs (read the file list from $doclist via xargs).
scan() { xargs grep -rhoE "$1" < "$doclist" 2>/dev/null; }

# 1) Test-class references: `FooTest` or `FooTest.method` -> must exist under src/test as FooTest.java
scan '`[A-Z][A-Za-z0-9]+Test' | sed -e 's/`//' -e 's/\..*//' | sort -u | while IFS= read -r t; do
  [ -z "$t" ] && continue
  find src/test -name "$t.java" 2>/dev/null | grep -q . \
    || echo "test class referenced but not found: $t (expected src/test/.../$t.java)" >> "$broken"
done

# 2) Java-source references: `Bar.java` -> must exist somewhere under src/
scan '`[A-Za-z0-9_]+\.java`' | sed 's/`//g' | sort -u | while IFS= read -r j; do
  [ -z "$j" ] && continue
  find src -name "$j" 2>/dev/null | grep -q . \
    || echo "Java file referenced but not found: $j" >> "$broken"
done

# 3) TESTING.md case-number references: `cases 549-568`, `case 572` -> each number is a '## N.' heading
scan 'cases? [0-9]+(-[0-9]+)?' | grep -oE '[0-9]+' | sort -nu | while IFS= read -r n; do
  [ -z "$n" ] && continue
  grep -qE "^## $n\." TESTING.md 2>/dev/null \
    || echo "TESTING.md case referenced but no '## $n.' heading exists: $n" >> "$broken"
done

# 4) Relative Markdown links: [text](TARGET) -> the target must exist, resolved relative to the file the
#    link appears in. http(s):// / mailto: / pure #anchor links are ignored; a trailing-slash or directory
#    target is checked as a directory; an #anchor and an optional "title" suffix are stripped first.
while IFS= read -r f; do
  [ -z "$f" ] && continue
  d="$(dirname "$f")"
  grep -oE '\]\([^)]+\)' "$f" 2>/dev/null | sed -e 's/^](//' -e 's/)$//' | while IFS= read -r tgt; do
    case "$tgt" in
      http://*|https://*|mailto:*|\#*|"") continue ;;
    esac
    path="${tgt%%#*}"      # strip #anchor
    path="${path%% *}"     # strip optional "title" suffix
    [ -z "$path" ] && continue
    resolved="$d/$path"
    case "$path" in
      */) [ -d "$resolved" ] || echo "broken link in $f -> $tgt (no dir $resolved)" >> "$broken" ;;
      *)  [ -e "$resolved" ] || echo "broken link in $f -> $tgt (no file $resolved)" >> "$broken" ;;
    esac
  done
done < "$doclist"

count="$(wc -l < "$broken" | tr -d ' ')"
scanned="$(wc -l < "$doclist" | tr -d ' ')"

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
