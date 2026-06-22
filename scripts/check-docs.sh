#!/usr/bin/env sh
# check-docs.sh — fail if the teaching docs reference a test class, Java source file, or TESTING.md case
# number that does not exist in the repo. Keeps the cross-referenced docs (README.md, docs/*.md) from
# silently rotting when something is renamed, moved, or deleted.
#
# Scope: the LIVING docs only — README.md, CONTRIBUTING.md, and docs/*.md EXCEPT docs/HISTORY.md, which is an archive that
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
[ -f CONTRIBUTING.md ] && printf '%s\n' "CONTRIBUTING.md" >> "$doclist"
find docs -maxdepth 1 -type f -name '*.md' ! -name 'HISTORY.md' 2>/dev/null | sort >> "$doclist"

if [ ! -s "$doclist" ]; then
  echo "No living docs found to check."
  exit 0
fi

# grep across exactly the living docs (read the file list from $doclist via xargs).
scan() { xargs grep -rhoE "$1" < "$doclist" 2>/dev/null; }

# slug TEXT -> a GitHub-style heading anchor: lowercase, drop punctuation (keep alnum/space/hyphen),
# spaces->hyphens, collapse repeats, trim. Approximate but covers ordinary headings.
#
# Slug limitations (intentional, pinned by check-docs-selftest.sh) -- this diverges from GitHub when:
#   * consecutive removed punctuation / multiple hyphens collapse to ONE hyphen here, whereas GitHub keeps
#     them (e.g. "C++ & Friends" -> here "c-friends", GitHub "c--friends"; "A -- B" -> here "a-b").
# Underscores ARE kept (matching GitHub, e.g. "read_file Helper" -> "read_file-helper"). For ordinary prose
# headings (letters, digits, spaces, single hyphens, underscores, attached punctuation like ".", "(", ")",
# ",") the two agree. Author anchors to match THIS slug; the self-test guards the behavior from drift.
slug() {
  printf '%s\n' "$1" | tr '[:upper:]' '[:lower:]' \
    | sed -e 's/[^a-z0-9_ -]//g' -e 's/ /-/g' -e 's/--*/-/g' -e 's/^-//' -e 's/-$//'
}

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
#    link appears in. http(s):// / mailto: links are ignored. For a target with an #anchor (cross-file
#    FILE.md#heading, or same-file #heading), the anchor must match a heading in the target .md file.
#    A trailing-slash/directory target is checked as a directory; an optional "title" suffix is stripped.
while IFS= read -r f; do
  [ -z "$f" ] && continue
  d="$(dirname "$f")"
  grep -oE '\]\([^)]+\)' "$f" 2>/dev/null | sed -e 's/^](//' -e 's/)$//' | while IFS= read -r tgt; do
    case "$tgt" in
      http://*|https://*|mailto:*|"") continue ;;
    esac
    # split anchor (after first #) from path (before it); strip an optional "title" suffix from the path.
    anchor=""
    case "$tgt" in *"#"*) anchor="${tgt#*#}" ;; esac
    path="${tgt%%#*}"
    path="${path%% *}"

    # Determine the .md file whose headings an anchor would refer to.
    mdfile=""
    if [ -z "$path" ]; then
      # pure #anchor -> same file
      mdfile="$f"
    else
      resolved="$d/$path"
      case "$path" in
        */) [ -d "$resolved" ] || echo "broken link in $f -> $tgt (no dir $resolved)" >> "$broken" ;;
        *)  if [ -e "$resolved" ]; then
              case "$path" in *.md) mdfile="$resolved" ;; esac
            else
              echo "broken link in $f -> $tgt (no file $resolved)" >> "$broken"
            fi ;;
      esac
    fi

    # Validate the anchor against the target file's headings (only when it's a readable .md).
    if [ -n "$anchor" ] && [ -n "$mdfile" ] && [ -f "$mdfile" ]; then
      want="$(printf '%s' "$anchor" | tr '[:upper:]' '[:lower:]')"
      slugfile="$work/slugs"
      : > "$slugfile"
      grep -E '^#{1,6}[[:space:]]' "$mdfile" 2>/dev/null \
        | sed 's/^#\{1,6\}[[:space:]][[:space:]]*//' | while IFS= read -r h; do
            slug "$h" >> "$slugfile"
          done
      grep -qxF "$want" "$slugfile" \
        || echo "broken anchor in $f -> $tgt (no heading '#$anchor' in $mdfile)" >> "$broken"
    fi
  done
done < "$doclist"

# 5) Every script a CI workflow (or composite action) invokes by path must exist -- catches a workflow that
#    references a file that was never committed (the failure mode that broke CI before). Scans all YAML under
#    .github (workflows AND composite-action action.yml). Recognizes these invocation shapes, including a
#    `run:` block that cd's first: `scripts/<path>.sh` anywhere; `bash <path>.sh` / `sh <path>.sh`; and a
#    direct `./<path>.sh`. Globs and variables (e.g. `sh -n "$f"`, `scripts/*.sh`) are not matched.
if [ -d .github ]; then
  yamls="$work/yamls"
  find .github \( -name '*.yml' -o -name '*.yaml' \) -type f 2>/dev/null > "$yamls" || true
  if [ -s "$yamls" ]; then
    {
      xargs grep -rhoE 'scripts/[A-Za-z0-9_./-]+\.sh' < "$yamls" 2>/dev/null
      xargs grep -rhoE '(bash|sh) +[A-Za-z0-9_./-]+\.sh' < "$yamls" 2>/dev/null \
        | sed -e 's/^bash  *//' -e 's/^sh  *//'
      xargs grep -rhoE '\./[A-Za-z0-9_./-]+\.sh' < "$yamls" 2>/dev/null | sed -e 's#^\./##'
    } | sort -u | while IFS= read -r s; do
      [ -z "$s" ] && continue
      case "$s" in *'*'*|*'$'*) continue ;; esac   # skip any glob/variable that slipped through
      [ -f "$s" ] || echo "workflow invokes a script that does not exist: $s" >> "$broken"
    done
  fi
fi

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
