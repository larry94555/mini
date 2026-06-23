#!/bin/sh
# Parse the "[integration] <label> (<dep>) ran|skipped" markers emitted by IntegrationGate (captured in the
# surefire reports) and print a one-line dependency-coverage report: which real dependencies were exercised.
# Fail if any dependency that CI requires (IMINI_REQUIRE_<DEP>=1) was skipped instead of run.
#
# Usage: scripts/integration-coverage.sh [reports-dir]   (default: target/surefire-reports)
set -eu

REPORTS="${1:-target/surefire-reports}"

if [ ! -d "$REPORTS" ]; then
  echo "::error::no surefire reports at $REPORTS — did the tests run?"
  exit 1
fi

# Collect every integration marker line across all report files.
markers="$(grep -rho '\[integration\] .* (.*) \(ran\|skipped\)' "$REPORTS" 2>/dev/null || true)"

# Dependencies to inspect. Keep in sync with the IMINI_REQUIRE_* switches.
deps="persistence node git json html network model"

status=0
summary=""
for dep in $deps; do
  ran=$(printf '%s\n' "$markers" | grep -c "($dep) ran" || true)
  skipped=$(printf '%s\n' "$markers" | grep -c "($dep) skipped" || true)
  required="no"
  # IMINI_REQUIRE_<DEP> -> uppercase the dep name.
  var="IMINI_REQUIRE_$(printf '%s' "$dep" | tr '[:lower:]' '[:upper:]')"
  eval "val=\${$var:-}"
  case "$val" in
    1|true|TRUE|yes|YES) required="yes" ;;
  esac

  state="ran=$ran skipped=$skipped"
  if [ "$required" = "yes" ]; then
    if [ "$ran" -eq 0 ]; then
      echo "::error::dependency '$dep' is required ($var set) but no test ran against it ($state)"
      status=1
    fi
    if [ "$skipped" -gt 0 ]; then
      echo "::error::dependency '$dep' is required ($var set) but $skipped test(s) skipped it"
      status=1
    fi
    state="$state required"
  fi
  summary="$summary $dep[$state]"
done

echo "integration dependency coverage:$summary"
if [ "$status" -ne 0 ]; then
  echo "::error::one or more required dependencies were not exercised — see above"
  exit 1
fi
echo "all required dependencies were exercised."
