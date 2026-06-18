#!/usr/bin/env sh
# Shared helpers for the imini POSIX scripts (macOS, Linux, WSL, Git Bash on Windows).
# Override the base URL with:  IMINI_URL=http://host:port ask.sh "..."
IMINI_URL="${IMINI_URL:-http://localhost:8080}"

# Escape a string for safe embedding inside a JSON double-quoted value (backslash + quote).
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# POST a JSON body to a path:  api_post /ask '{"question":"hi"}'
api_post() {
  curl -s -X POST "$IMINI_URL$1" -H "Content-Type: application/json" -d "$2"
}

# POST and stream the response (Server-Sent Events):  api_stream /chat/stream '{...}'
api_stream() {
  curl -N -s -X POST "$IMINI_URL$1" -H "Content-Type: application/json" -d "$2"
}

# GET a path:  api_get /runs
api_get() {
  curl -s "$IMINI_URL$1"
}

# Resolve the directory of the calling script (so scripts can be run from anywhere).
script_dir() {
  CDPATH= cd -- "$(dirname -- "$1")" && pwd -P
}
