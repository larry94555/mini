#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${2:-}" = "" ]; then
  echo 'Usage: ./stream.sh SESSION_ID "your message"'
  echo 'Streams the run as Server-Sent Events (token/log/answer) live in this terminal.'
  echo 'Interrupt it from another terminal with: ./interrupt.sh SESSION_ID'
  exit 1
fi
api_stream /chat/stream "{\"sessionId\":\"$(json_escape "$1")\",\"message\":\"$(json_escape "$2")\"}"
echo
