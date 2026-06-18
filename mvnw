#!/usr/bin/env sh
# Lightweight Maven wrapper for imini (macOS, Linux, WSL, Git Bash). Windows-native: mvnw.cmd.
# Prefers a system 'mvn'; otherwise downloads a pinned Apache Maven into .maven/ once and uses it.
set -eu

BASEDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
PROPS="$BASEDIR/.mvn/wrapper/maven-wrapper.properties"

prop() { [ -f "$PROPS" ] && sed -n "s/^$1=//p" "$PROPS" | tr -d '\r' | head -n1 || true; }

VER="$(prop distributionVersion)"; [ -n "$VER" ] || VER="3.9.9"
URL="$(prop distributionUrl)"
[ -n "$URL" ] || URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VER/apache-maven-$VER-bin.zip"
SUM="$(prop distributionSha256Sum)"

# 1) Use a system Maven if present.
if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

# 2) Otherwise use (or fetch) a wrapper-managed Maven under .maven/.
MVN_HOME="$BASEDIR/.maven/apache-maven-$VER"
MVN_BIN="$MVN_HOME/bin/mvn"
if [ ! -x "$MVN_BIN" ]; then
  echo "[mvnw] no system Maven found; downloading Apache Maven $VER (one time)..."
  mkdir -p "$BASEDIR/.maven"
  ZIP="$BASEDIR/.maven/apache-maven-$VER-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$ZIP"
  else
    echo "[mvnw] need curl or wget to download Maven. Install Maven manually, or use Docker." >&2
    exit 1
  fi
  if [ -n "${SUM:-}" ]; then
    if command -v shasum >/dev/null 2>&1; then
      echo "$SUM  $ZIP" | shasum -a 256 -c - || { echo "[mvnw] checksum mismatch" >&2; exit 1; }
    elif command -v sha256sum >/dev/null 2>&1; then
      echo "$SUM  $ZIP" | sha256sum -c - || { echo "[mvnw] checksum mismatch" >&2; exit 1; }
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP" -d "$BASEDIR/.maven"
  else
    echo "[mvnw] need 'unzip' to extract Maven. Install it, or use Docker." >&2
    exit 1
  fi
  rm -f "$ZIP"
fi
exec "$MVN_BIN" "$@"
