#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
if [ "${1:-}" = "" ]; then
  echo 'Usage: ./ask.sh "your question"'
  echo 'Example: ./ask.sh "What is the current top story on FoxNews.com?"'
  exit 1
fi
echo "Asking: $1"
api_post /ask "{\"question\":\"$(json_escape "$1")\"}"
echo
