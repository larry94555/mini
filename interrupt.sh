#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${1:-}" = "" ]; then echo "Usage: ./interrupt.sh SESSION_ID"; exit 1; fi
echo "Requesting interrupt of session $1 ..."
api_post /interrupt "{\"sessionId\":\"$(json_escape "$1")\"}"
echo
