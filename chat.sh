#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${2:-}" = "" ]; then
  echo 'Usage: ./chat.sh SESSION_ID "your message"'
  echo 'Example: ./chat.sh work1 "View pom.xml and tell me the dependencies"'
  echo 'Then continue the same conversation:'
  echo '         ./chat.sh work1 "Now add a comment above the jsoup dependency"'
  exit 1
fi
api_post /chat "{\"sessionId\":\"$(json_escape "$1")\",\"message\":\"$(json_escape "$2")\"}"
echo
