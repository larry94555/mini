#!/usr/bin/env sh
# Pin the wrapper's Maven download by recording its SHA-256 in .mvn/wrapper/maven-wrapper.properties.
# Run once (needs network + curl/wget + sha256sum/shasum). After this, ./mvnw and mvnw.cmd verify the
# download against the recorded hash. This writes a VERIFIED value -- it does not guess.
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

if command -v sha256sum >/dev/null 2>&1; then SUM="$(sha256sum "$TMP" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then SUM="$(shasum -a 256 "$TMP" | awk '{print $1}')"
else echo "need sha256sum or shasum" >&2; rm -f "$TMP"; exit 1; fi
rm -f "$TMP"

TMPP="$(mktemp)"
sed "s/^distributionSha256Sum=.*/distributionSha256Sum=$SUM/" "$PROPS" > "$TMPP" && mv "$TMPP" "$PROPS"
echo "pinned distributionSha256Sum=$SUM"
echo "commit $PROPS to enforce it for everyone."
