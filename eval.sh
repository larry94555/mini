#!/usr/bin/env sh
# Run the behavioral eval suite (evals/cases.json) against a running imini.
# POSIX equivalent of eval.bat. Requires curl and jq.
set -eu
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
. "$HERE/scripts/common.sh"

if ! command -v jq >/dev/null 2>&1; then
  echo "[ERROR] this eval runner needs 'jq' to parse evals/cases.json."
  echo "  macOS: brew install jq    Linux: sudo apt install jq"
  exit 1
fi
CASES="$HERE/evals/cases.json"
[ -f "$CASES" ] || { echo "[ERROR] not found: $CASES"; exit 1; }

total=0; passed=0
count="$(jq 'length' "$CASES")"
i=0
while [ "$i" -lt "$count" ]; do
  name="$(jq -r ".[$i].name" "$CASES")"
  question="$(jq -r ".[$i].question" "$CASES")"
  total=$((total + 1))
  answer="$(api_post /ask "{\"question\":\"$(json_escape "$question")\"}")"
  ok=1
  ec_count="$(jq -r ".[$i].expect_contains | length" "$CASES")"
  j=0
  while [ "$j" -lt "$ec_count" ]; do
    needle="$(jq -r ".[$i].expect_contains[$j]" "$CASES")"
    case "$answer" in *"$needle"*) : ;; *) ok=0 ;; esac
    j=$((j + 1))
  done
  if [ "$ok" -eq 1 ]; then passed=$((passed + 1)); echo "PASS  $name"; else echo "FAIL  $name"; fi
  i=$((i + 1))
done
echo
echo "evals: $passed/$total passed"
[ "$passed" -eq "$total" ]
