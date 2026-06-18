#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
echo "Concurrency status (limit / active / queued):"
api_get /runs
echo
