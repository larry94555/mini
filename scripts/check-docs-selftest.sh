#!/usr/bin/env sh
# check-docs-selftest.sh — regression guard for the anchor/slug logic in check-docs.sh.
# Builds a throwaway repo layout, copies the REAL check-docs.sh into it, and runs it against fixture docs
# whose headings use tricky punctuation. The "known-good" anchors below are HARD-CODED literal slugs (not
# recomputed), so if the slug algorithm ever drifts, a previously-valid anchor stops matching and this test
# fails — locking the GitHub-style behavior in place. POSIX sh; dependency-free.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
SCRIPT="$ROOT/scripts/check-docs.sh"
[ -f "$SCRIPT" ] || { echo "FAIL: $SCRIPT not found"; exit 1; }

fails=0
ck() { if [ "$1" = "1" ]; then echo "PASS $2"; else echo "FAIL $2"; fails=$((fails + 1)); fi; }

# --- Scenario A: a doc with tricky headings + 2 valid and 2 broken anchors -> expect exit 1, exactly 2 broken
a="$(mktemp -d)"
mkdir -p "$a/scripts" "$a/docs"
cp "$SCRIPT" "$a/scripts/check-docs.sh"
printf '# tmp\n' > "$a/README.md"

cat > "$a/docs/TARGET.md" <<'MD'
# Target

## 4. How each branch is proven (the golden-trace suite)
Body.

## Streaming, Tracing, and Cost (v2.0)
Body.
MD

cat > "$a/docs/SOURCE.md" <<'MD'
# Source

## Known Good Section
Body.

Valid cross-file [g1](TARGET.md#4-how-each-branch-is-proven-the-golden-trace-suite).
Valid cross-file [g2](TARGET.md#streaming-tracing-and-cost-v20).
Valid same-file [g3](#known-good-section).
Broken cross-file [b1](TARGET.md#no-such-heading-here).
Broken same-file [b2](#totally-bogus).
MD

outA="$(mktemp)"
set +e
sh "$a/scripts/check-docs.sh" > "$outA" 2>&1
codeA=$?
set -e

ck "$( [ "$codeA" -eq 1 ] && echo 1 || echo 0 )" "scenario A exits 1 (broken anchors present)"
nbroken="$(grep -c '\[BROKEN\]' "$outA" || true)"
ck "$( [ "$nbroken" -eq 2 ] && echo 1 || echo 0 )" "scenario A reports exactly 2 broken (got $nbroken)"
ck "$(grep -q 'no-such-heading-here' "$outA" && echo 1 || echo 0)" "scenario A flags the broken cross-file anchor"
ck "$(grep -q 'totally-bogus' "$outA" && echo 1 || echo 0)" "scenario A flags the broken same-file anchor"
ck "$(grep -q '4-how-each-branch-is-proven-the-golden-trace-suite' "$outA" && echo 0 || echo 1)" "scenario A does NOT flag the valid §4-style anchor"
ck "$(grep -q 'streaming-tracing-and-cost-v20' "$outA" && echo 0 || echo 1)" "scenario A does NOT flag the valid v2.0 anchor"
ck "$(grep -q 'known-good-section' "$outA" && echo 0 || echo 1)" "scenario A does NOT flag the valid same-file anchor"

# --- Scenario B: only valid anchors -> expect exit 0 / OK
b="$(mktemp -d)"
mkdir -p "$b/scripts" "$b/docs"
cp "$SCRIPT" "$b/scripts/check-docs.sh"
printf '# tmp\n' > "$b/README.md"
cat > "$b/docs/TARGET.md" <<'MD'
# Target

## 4. How each branch is proven (the golden-trace suite)
Body.
MD
cat > "$b/docs/SOURCE.md" <<'MD'
# Source
Only valid [ok](TARGET.md#4-how-each-branch-is-proven-the-golden-trace-suite).
MD
outB="$(mktemp)"
set +e
sh "$b/scripts/check-docs.sh" > "$outB" 2>&1
codeB=$?
set -e
ck "$( [ "$codeB" -eq 0 ] && echo 1 || echo 0 )" "scenario B exits 0 (all anchors valid)"
ck "$(grep -q '^OK:' "$outB" && echo 1 || echo 0)" "scenario B prints OK"

# --- Scenario C: WARN_ONLY downgrades the broken scenario to exit 0
outC="$(mktemp)"
set +e
WARN_ONLY=1 sh "$a/scripts/check-docs.sh" > "$outC" 2>&1
codeC=$?
set -e
ck "$( [ "$codeC" -eq 0 ] && echo 1 || echo 0 )" "scenario C WARN_ONLY exits 0 despite breakage"

rm -rf "$a" "$b" "$outA" "$outB" "$outC"

echo
if [ "$fails" -eq 0 ]; then
  echo "check-docs self-test: OK"
  exit 0
fi
echo "check-docs self-test: $fails assertion(s) FAILED"
exit 1
