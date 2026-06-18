#!/usr/bin/env sh
# Pin the wrapper's Maven download by recording its SHA-512 in .mvn/wrapper/maven-wrapper.properties.
# Run once after a version bump (needs network + curl/wget + sha512sum/shasum). Writes a VERIFIED value.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
PROPS="$ROOT/.mvn/wrapper/maven-wrapper.properties"
[ -f "$PROPS" ] || { echo "not found: $PROPS" >&2; exit 1; }

URL="$(sed -n 's/^distributionUrl=//p' "$PROPS" | tr -d '\r' | head -n1)"
[ -n "$URL" ] || { echo "no distributionUrl in $PROPS" >&2; exit 1; }

TMP="$(mktemp)"
echo "downloading $URL ..."
if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$TMP"
elif command -v wget >/dev/null 2>&1; then wget -q "$URL" -O "$TMP"
else echo "need curl or wget" >&2; rm -f "$TMP"; exit 1; fi

if command -v sha512sum >/dev/null 2>&1; then SUM="$(sha512sum "$TMP" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then SUM="$(shasum -a 512 "$TMP" | awk '{print $1}')"
else echo "need sha512sum or shasum" >&2; rm -f "$TMP"; exit 1; fi
rm -f "$TMP"

TMPP="$(mktemp)"
sed "s/^distributionSha512Sum=.*/distributionSha512Sum=$SUM/" "$PROPS" > "$TMPP" && mv "$TMPP" "$PROPS"
echo "pinned distributionSha512Sum=$SUM"
echo "commit $PROPS to enforce it for everyone."
