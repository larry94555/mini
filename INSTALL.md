# How to install and run imini

This guide covers four platforms: **macOS**, **Linux**, **Windows Subsystem for Linux (WSL)**, and
**Windows**. The steps are the same idea everywhere — install Java, install llama-server, start the app —
but the commands differ per platform. Jump to your platform; the macOS/Linux/WSL path is generally the
smoothest.

You only do steps 1–3 **once**. After that, starting the app is just step 4.

---

## What you'll end up with

A small program running on your own machine that you can ask questions. It uses a local AI model (no
cloud, no API key). It has two pieces that start together:

- **llama-server** — the AI engine (you install this in step 2).
- **imini** — the assistant wrapper around it (the code you downloaded).

---

## Quick-start summary

| Platform | Java install | llama-server install | Launch |
|---|---|---|---|
| macOS | `brew install temurin@17` | `brew install llama.cpp` | `./run.sh` |
| Linux | `sudo apt install openjdk-17-jdk` | clone + cmake (see below) | `./run.sh` |
| WSL | `sudo apt install openjdk-17-jdk` | clone + cmake (see below) | `./run.sh` |
| Windows | `winget install EclipseAdoptium.Temurin.17.JDK` | pre-built zip (see below) | `run.bat` |

---

## Step 1 — Install Java 17+

Java 17 or higher is required. Choose the command for your platform:

**macOS (Homebrew):**
```sh
brew install temurin@17
```
If you don't have Homebrew: https://brew.sh — or download the Temurin 17 installer directly from
https://adoptium.net.

**Linux (Debian / Ubuntu):**
```sh
sudo apt update && sudo apt install openjdk-17-jdk
```

**WSL (Windows Subsystem for Linux):**

Open your WSL terminal (e.g. Ubuntu from the Start menu) and run:
```sh
sudo apt update && sudo apt install openjdk-17-jdk
```
All remaining steps in WSL are done inside the WSL terminal, not in Windows PowerShell.

**Fedora / RHEL:**
```sh
sudo dnf install java-17-openjdk-devel
```

**Any platform — SDKMAN! (alternative to the above):**
```sh
curl -s "https://get.sdkman.io" | bash
# open a new terminal, then:
sdk install java 17-tem
```

**Verify — all platforms:**
```sh
java -version
```
You should see a version number starting with **17** or higher.

---

### Step 1 (Windows — native) — Install Java 17+

> This section is for running imini in a native Windows terminal (`cmd` / PowerShell), not inside WSL.
> If you are using WSL, follow the Linux/WSL instructions above.

1. Open **PowerShell** (click Start → type PowerShell → open it).
2. Run:
   ```
   winget install EclipseAdoptium.Temurin.17.JDK
   ```
   If `winget` is not available, download the Temurin 17 JDK installer for Windows from
   https://adoptium.net and run it.
3. **Close PowerShell and open a new one** — this refreshes the PATH.
4. Verify:
   ```
   java -version
   ```

---

## Step 2 — Install Maven (optional — the wrapper handles it)

The repo ships a Maven wrapper (`./mvnw` / `mvnw.cmd`), and `run.sh` / `run.bat` use it automatically.
You do **not** need to install Maven separately — if none is on your PATH, the wrapper downloads a pinned
copy into `.maven/` once.

If you prefer a system install:

**macOS:** `brew install maven`
**Linux / WSL:** `sudo apt install maven`
**Fedora:** `sudo dnf install maven`
**SDKMAN!:** `sdk install maven`

Verify (any platform): `./mvnw -version`

---

## Step 3 — Install llama-server (the AI engine)

### Further reading

For a comprehensive, platform-by-platform guide to installing llama.cpp (including GPU acceleration options
for NVIDIA, AMD, and Apple Metal), see:
**[Getting Started with LLaMA.cpp — llama-cpp.com](https://llama-cpp.com/getting-started/)**

The instructions below cover the most common paths for each platform.

### macOS

```sh
brew install llama.cpp
```

This puts `llama-server` on your PATH. Done.

---

### Linux

The recommended approach is to build llama.cpp from source using CMake, which produces a binary tuned for
your CPU:

```sh
# Install build tools (Debian/Ubuntu)
sudo apt update && sudo apt install -y git cmake build-essential

# Clone the repository
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp

# Build
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j$(nproc)

# Add the build output directory to your PATH so llama-server is callable from anywhere.
# Add this line to your shell profile (~/.bashrc, ~/.zshrc, etc.), then reload it:
#   export PATH="$HOME/llama.cpp/build/bin:$PATH"
# To apply immediately in the current terminal:
echo 'export PATH="$HOME/llama.cpp/build/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

**Fedora / RHEL build tools:**
```sh
sudo dnf install -y git cmake gcc-c++ make
```

Verify: `llama-server --version`

**Pre-built binary alternative:** If you prefer not to build, download a Linux release binary from
https://github.com/ggml-org/llama.cpp/releases — look for a `linux-x64` asset. Extract it and put
`llama-server` on your PATH or in the imini folder.

---

### WSL (Windows Subsystem for Linux)

WSL runs a full Linux environment, so you build llama.cpp the same way as on Linux. The `nix`
package-manager approach that is sometimes suggested does not work reliably in WSL — building from source
with CMake is the recommended method:

```sh
# Install build tools inside WSL
sudo apt update && sudo apt install -y git cmake build-essential

# Clone llama.cpp
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp

# Build (this takes a few minutes)
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j$(nproc)

# Add the build output directory to your PATH so llama-server is callable from anywhere.
# Add this to your shell profile (~/.bashrc), then reload it:
#   export PATH="$HOME/llama.cpp/build/bin:$PATH"
# To apply immediately in the current terminal:
echo 'export PATH="$HOME/llama.cpp/build/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

Verify from the WSL terminal: `llama-server --version`

> **Note:** Run imini entirely inside the WSL terminal. Once it is running you can open
> `http://localhost:8080` in your Windows browser as normal.

---

### Windows (native)

> For WSL users: follow the WSL section above instead.

1. Go to https://github.com/ggml-org/llama.cpp/releases
2. Under the most recent release, open the **Assets** list and download the Windows CPU build. Its name
   looks like:
   ```
   llama-bXXXX-bin-win-avx2-x64.zip
   ```
   (`bXXXX` is a build number; `avx2` is the standard CPU build — use it unless you have an NVIDIA GPU
   configured.)
3. **Extract the zip.** Right-click → **Extract All…**
4. Find **`llama-server.exe`** in the extracted folder.
5. Copy **`llama-server.exe`** and all the **`.dll` files** that sit next to it into your imini folder
   (the same folder that contains `run.bat`). If in doubt, copy everything from the extracted folder.

   *(Advanced: add the extracted folder to your Windows PATH instead. Copying is simpler.)*

---

## About the AI model

You do **not** need to download the model manually. imini tells llama-server to download it
automatically the first time it runs (~2 GB; progress is written to `llama-server.log` in the imini
folder). Expect the first startup to take 5–20 minutes depending on your internet connection.

---

## Step 4 — Start imini

### macOS / Linux / WSL

From the imini folder, make the scripts executable once, then launch:

```sh
chmod +x *.sh scripts/*.sh
./run.sh
```

### Windows (native)

Open a Command Prompt terminal in the imini folder (open the folder in File Explorer, click the address
bar, type `cmd`, press Enter), then run:

```
run.bat
```

---

**You are running when you see:**
```
llama-server is ready.
Started MiniAgentApplication in ... seconds
```
Leave the terminal open — closing it stops the app. The first run is slow (library download + 2 GB model
download). Open `llama-server.log` to watch the model download progress.

---

## Step 5 — Ask it something

Open a **second** terminal in the imini folder and run:

**macOS / Linux / WSL:**
```sh
./ask.sh "What is the capital of France?"
```

**Windows (native):**
```
ask.bat "What is the capital of France?"
```

The answer prints after a few moments. Watch the first terminal — you'll see the model generating tokens.
Press **Ctrl+C** in the first terminal to stop.

---

## Docker alternative (any platform)

If you prefer not to install Java, Maven, or llama.cpp at all, use the container stack (Docker Desktop
on macOS/Windows, Docker Engine on Linux/WSL):

```sh
docker compose -f docker-compose.yml up --build
```

Or, using the published multi-arch image (no local build):

```sh
docker compose -f docker-compose.published.yml up
```

See [`docs/observability/`](docs/observability/) to add Prometheus/Grafana dashboards, and
[`docs/DEPLOY.md`](docs/DEPLOY.md) for the full deployment guide (health probes, Kubernetes).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `java: command not found` | Finish Step 1 and open a new terminal. |
| `llama-server` not found | Finish Step 3; or set `llama.manage-server=false` to connect to an external server. |
| `permission denied: ./run.sh` | Run `chmod +x *.sh scripts/*.sh` once. |
| Window flashed and closed (Windows) | Run from a terminal (Step 4) so the error message stays visible. |
| "llama-server not ready after 120s" | The model is still downloading. Open `llama-server.log` to confirm; wait and try again. |
| Seems frozen with no output | One of the two large downloads is in progress. Check `llama-server.log`. |
| "port already in use" | Something else is using port 8080 or 8081. Close competing apps, or change the ports in `application.properties` (8080) and `LlamaServerManager.java` (8081). |
| macOS "developer cannot be verified" for `llama-server` | Clear the quarantine flag: `xattr -d com.apple.quarantine /path/to/llama-server` |
| WSL: can't reach app from Windows browser | Run `./run.sh` inside the WSL terminal; then open `http://localhost:8080` in your Windows browser. |
| cmake not found (Linux/WSL build) | `sudo apt install cmake build-essential` (Debian/Ubuntu) or `sudo dnf install cmake gcc-c++` (Fedora). |
| llama.cpp build errors | Make sure build tools are installed; try `cmake --build build --config Release -j1` (single thread) to see the error clearly. |
