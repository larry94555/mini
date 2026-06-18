#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${2:-}" = "" ]; then echo 'Usage: ./steer.sh SESSION_ID "guidance for the running agent"'; exit 1; fi
api_post /steer "{\"sessionId\":\"$(json_escape "$1")\",\"message\":\"$(json_escape "$2")\"}"
echo
