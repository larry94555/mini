#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${1:-}" = "" ]; then
  echo 'Usage: ./plan.sh "your request"'
  echo 'Runs the request in PLAN mode: the agent proposes edits/commands but executes nothing.'
  exit 1
fi
api_post /ask "{\"question\":\"$(json_escape "$1")\",\"mode\":\"plan\"}"
echo
