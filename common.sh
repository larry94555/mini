#!/usr/bin/env sh
# Shared helpers for the imini POSIX scripts (macOS, Linux, WSL, Git Bash on Windows).
# Override the base URL with:  IMINI_URL=http://host:port ask.sh "..."
# On WSL, if localhost doesn't resolve to the running server, try 127.0.0.1 explicitly:
#   IMINI_URL=http://127.0.0.1:8080 ./ask.sh "..."
IMINI_URL="${IMINI_URL:-http://localhost:8080}"
# Strip any stray carriage return (e.g. if a .sh was checked out with CRLF line endings on Windows),
# which would otherwise corrupt the URL and make every request fail silently.
IMINI_URL=$(printf '%s' "$IMINI_URL" | tr -d '\r')

# Print a clear, actionable message when the server can't be reached. Without this, `curl -s` swallows
# the error and the script just returns with no output -- which looks like "no response".
_imini_unreachable() {
  printf '%s\n' "" >&2
  printf '%s\n' "[imini] Could not get a response from imini at ${IMINI_URL} (curl exit ${1:-?})." >&2
  printf '%s\n' "        Is the server running? In another terminal run ./run.sh and wait until you see" >&2
  printf '%s\n' "        'llama-server is ready.' and 'Started MiniAgentApplication ...'." >&2
  printf '%s\n' "        On WSL, if run.sh is running but this still fails, try the IPv4 address:" >&2
  printf '%s\n' "          IMINI_URL=http://127.0.0.1:8080 $(basename "$0" 2>/dev/null || echo ./ask.sh) \"your question\"" >&2
}

# Run a curl request, capture the body, and turn a silent failure into a clear message.
# Streams the body to stdout on success; on connection failure prints a diagnostic (curl's own error via
# -sS, plus our hint) and returns non-zero. -sS keeps the progress meter hidden but SHOWS errors.
# A short --connect-timeout means an unreachable server fails fast with a message instead of hanging;
# there is deliberately no overall timeout, because model generation can legitimately take a while.
_imini_request() {
  # Temporarily disable errexit so we can inspect curl's exit status (callers may use `set -e`).
  case "$-" in *e*) _had_e=1; set +e ;; *) _had_e=0 ;; esac
  body=$(curl -sS --connect-timeout 5 "$@")
  rc=$?
  [ "$_had_e" = "1" ] && set -e
  if [ "$rc" -ne 0 ]; then
    _imini_unreachable "$rc"
    return "$rc"
  fi
  if [ -z "$body" ]; then
    printf '%s\n' "" >&2
    printf '%s\n' "[imini] Reached ${IMINI_URL} but got an empty response. The model may still be loading;" >&2
    printf '%s\n' "        check the run.sh window or llama-server.log, then try again." >&2
    return 0
  fi
  printf '%s' "$body"
}

# Escape a string for safe embedding inside a JSON double-quoted value (backslash + quote).
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# POST a JSON body to a path:  api_post /ask '{"question":"hi"}'
api_post() {
  _imini_request -X POST "$IMINI_URL$1" -H "Content-Type: application/json" -d "$2"
}

# POST and stream the response (Server-Sent Events):  api_stream /chat/stream '{...}'
api_stream() {
  case "$-" in *e*) _had_e=1; set +e ;; *) _had_e=0 ;; esac
  curl -N -sS --connect-timeout 5 -X POST "$IMINI_URL$1" -H "Content-Type: application/json" -d "$2"
  rc=$?
  [ "$_had_e" = "1" ] && set -e
  [ "$rc" -ne 0 ] && _imini_unreachable "$rc"
  return "$rc"
}

# GET a path:  api_get /runs
api_get() {
  _imini_request "$IMINI_URL$1"
}

# Resolve the directory of the calling script (so scripts can be run from anywhere).
script_dir() {
  CDPATH= cd -- "$(dirname -- "$1")" && pwd -P
}
