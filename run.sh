#!/usr/bin/env sh
# imini launcher for macOS, Linux, and WSL (and Git Bash on Windows).
# The Windows-native equivalent is run.bat.
set -eu
cd -- "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"

echo "============================================"
echo "  imini launcher"
echo "============================================"
echo

# OS label for install hints
OS="$(uname -s 2>/dev/null || echo unknown)"
case "$OS" in
  Darwin) PKG_JAVA="brew install temurin@17"; PKG_MVN="brew install maven"; PKG_LLAMA="brew install llama.cpp" ;;
  Linux)  PKG_JAVA="sudo apt install openjdk-17-jdk   # or: sdk install java 17-tem";
          PKG_MVN="sudo apt install maven             # or: sdk install maven";
          PKG_LLAMA="see https://github.com/ggml-org/llama.cpp (build or download a release)" ;;
  *)      PKG_JAVA="install a JDK 17+"; PKG_MVN="install Maven"; PKG_LLAMA="install llama.cpp" ;;
esac

echo "[1/4] Checking for Java..."
if ! command -v java >/dev/null 2>&1; then
  echo
  echo "  [ERROR] Java was not found. Install Java 17 or newer, then run this again."
  echo "      $PKG_JAVA"
  exit 1
fi
java -version
echo

echo "[2/4] Checking for llama-server..."
if command -v llama-server >/dev/null 2>&1; then
  echo "  Found llama-server on your PATH. Good."
elif [ -x "./llama-server" ]; then
  echo "  Found ./llama-server in this folder. Good."
else
  echo "  [WARNING] llama-server was not found on your PATH or in this folder."
  echo "  The app will still start, but cannot run the model until you add it:"
  echo "      $PKG_LLAMA"
  echo "  (Or set llama.manage-server=false to connect to an already-running server.)"
fi
echo

echo "[3/4] Locating Maven (the build tool)..."
# Prefer the bundled wrapper. Invoke it via 'sh' so it works even if the file's executable bit was lost
# (e.g. checked into git as non-executable); fall back to a system Maven.
if [ -f "./mvnw" ]; then
  USE_WRAPPER=1
  echo "  Using Maven: ./mvnw (wrapper)"
elif command -v mvn >/dev/null 2>&1; then
  USE_WRAPPER=0
  echo "  Using Maven: mvn"
else
  echo
  echo "  [ERROR] Maven was not found. Install it, then run this again."
  echo "      $PKG_MVN"
  exit 1
fi
echo

echo "[4/4] Building and starting imini..."
echo
echo "  NOTE: the first run is slow and may look quiet."
echo "    - It downloads Java libraries (a few minutes)."
echo "    - Then it downloads the AI model, about 2 GB (5-20 minutes);"
echo "      progress is written to llama-server.log"
echo "  When you see 'llama-server is ready.' and 'Started MiniAgentApplication ...',"
echo "  the app is running at http://localhost:8080 -- leave this window open."
echo "  Press Ctrl+C to stop it later."
echo
if [ "$USE_WRAPPER" = "1" ]; then
  exec sh ./mvnw spring-boot:run
else
  exec mvn spring-boot:run
fi
