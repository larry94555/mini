# How to install and run imini (step by step)

This is written for someone who isn't a programmer but is comfortable following
clear instructions. You'll install two free tools, then start the app.

You only do steps 1 and 2 **once**. After that, starting the app is just step 4.

---

## What you'll end up with

A small program running on your own PC that you can ask questions. It uses a local
AI model (no cloud, no API key). It has two pieces that start together:

- **llama-server** — the AI engine (you install this in step 2).
- **imini** — the "assistant" wrapper around it (the code you downloaded).

---

## Step 1 — Install Java (the engine that runs imini)

1. Click the Windows Start button, type **PowerShell**, and open it.
2. Copy-paste this line and press **Enter**:

   ```
   winget install EclipseAdoptium.Temurin.17.JDK
   ```

   Let it finish. (If `winget` isn't available, download "Temurin 17 (JDK)" for
   Windows from https://adoptium.net and run the installer instead.)
3. **Close PowerShell and open a new one** (this is important — it refreshes settings).
4. Check it worked by running:

   ```
   java -version
   ```

   You should see a version number that starts with **17** or higher. If you do, Java is ready.

---

## Step 2 — Install llama-server (the AI engine)

1. Go to the official downloads page: https://github.com/ggml-org/llama.cpp/releases
2. Under the most recent release, find the **Assets** list and download the
   **Windows CPU** build. Its name looks like:

   ```
   llama-bXXXX-bin-win-avx2-x64.zip
   ```

   (The `bXXXX` is just a build number. "avx2" is the normal CPU build — pick that one
   unless you specifically have an NVIDIA GPU set up.)
3. **Extract the zip.** Right-click the downloaded file → **Extract All…**
4. Open the extracted folder and find the file **`llama-server.exe`**.
5. The easiest way to let imini find it: **copy `llama-server.exe`** (and all the `.dll`
   files that sit next to it in that folder) **into your `imini` folder** — the same
   folder that has `run.bat`. Copy the whole contents of the extracted folder there if unsure.

   *(Advanced alternative: add the extracted folder to your Windows PATH instead. Copying is simpler.)*

You do **not** need to download the AI model yourself — imini tells llama-server to
download it automatically the first time it runs.

---

## Step 3 — Open the imini folder in a terminal

Running `run.bat` by double-clicking can make the window close too fast to read.
Open it in a terminal instead so you can see what it's doing:

1. Open the **imini** folder in File Explorer (the one containing `run.bat`).
2. Click the **address bar** at the top (where the folder path is shown).
3. Type **`cmd`** and press **Enter**. A black Command Prompt window opens, already
   pointing at the imini folder.

---

## Step 4 — Start it

1. In that Command Prompt window, type:

   ```
   run.bat
   ```

   and press **Enter**.
2. Watch the messages. The **first run is slow and sometimes looks quiet**:
   - It downloads Java libraries (a few minutes).
   - Then it downloads the AI model — about **2 GB** — which can take 5–20 minutes
     depending on your internet. Progress for this is written to the file
     **`llama-server.log`** inside the imini folder (you can open it to watch).
3. You're up and running when you see these lines:

   ```
   llama-server is ready.
   Started MiniAgentApplication in ... seconds
   ```

   **Leave this window open** — closing it stops the app.

---

## Step 5 — Ask it something

1. Open a **second** Command Prompt in the imini folder (repeat Step 3).
2. Type:

   ```
   ask.bat "What is the current top story on FoxNews.com?"
   ```

   The answer prints after a few moments. Watch the *first* window — you'll see the
   model "thinking" token by token.

To stop the app, go back to the first window and press **Ctrl+C**.

---

## If something goes wrong

- **The window flashed and closed when I double-clicked.** Run it from a terminal
  (Step 3–4) so the error stays on screen. Usually it means Java isn't installed yet (Step 1).
- **"llama-server.exe not found".** Finish Step 2 — copy `llama-server.exe` and its
  `.dll` files into the imini folder.
- **It says "llama-server not ready after 120s".** The model is probably still
  downloading. Open `llama-server.log` to confirm, wait until it finishes, then try
  `ask.bat` again.
- **It seems frozen with no text.** That's almost always one of the two big downloads
  in progress. Give it time; check `llama-server.log`.
- **"port already in use".** Something else is using port 8080 or 8081. Close other
  apps, or change the ports (8080 in `application.properties`, 8081 in
  `LlamaServerManager.java`).

---

# Running on macOS, Linux, or WSL

imini is plain Java, so the same code runs on any system with a JDK 17+. The steps mirror the Windows
walkthrough above; only the install commands and the launch script differ. Use the `.sh` scripts instead
of the `.bat` files.

## Step 1 — Install Java 17+

- **macOS (Homebrew):** `brew install temurin@17` (or any JDK 17+).
- **Debian/Ubuntu/WSL:** `sudo apt update && sudo apt install openjdk-17-jdk`
- **Fedora:** `sudo dnf install java-17-openjdk-devel`
- **Any (SDKMAN!):** `curl -s "https://get.sdkman.io" | bash`, then `sdk install java 17-tem`

Verify: `java -version` should print 17 or higher.

## Step 2 — Install Maven

- **macOS:** `brew install maven`
- **Debian/Ubuntu/WSL:** `sudo apt install maven`
- **Fedora:** `sudo dnf install maven`
- **Any (SDKMAN!):** `sdk install maven`

Verify: `mvn -version`.

## Step 3 — Install llama-server

The model engine is [llama.cpp](https://github.com/ggml-org/llama.cpp). On these systems the binary is
named `llama-server` (no `.exe`); imini detects this automatically.

- **macOS:** `brew install llama.cpp` (provides `llama-server` on your PATH).
- **Linux/WSL:** download a release binary from the llama.cpp releases page, or build from source
  (`cmake -B build && cmake --build build --config Release`); then put `llama-server` on your PATH or in
  the imini folder.
- You do **not** need to download the model yourself — imini tells llama-server to fetch it on first run
  (about 2 GB; progress goes to `llama-server.log`).

If you already run llama-server elsewhere, set `llama.manage-server=false` and point imini at it
(`llama.client-host` / `llama.port`).

## Step 4 — Start it

From the imini folder, make the scripts executable once, then launch:

```sh
chmod +x *.sh scripts/*.sh
./run.sh
```

Wait for `llama-server is ready.` and `Started MiniAgentApplication ...`. The app is at
http://localhost:8080. Leave the terminal open; press Ctrl+C to stop.

## Step 5 — Ask it something

In another terminal, from the same folder:

```sh
./ask.sh "Say hello in one sentence."
```

## Docker alternative (any OS)

If you would rather not install Java/Maven/llama.cpp at all, use the container stack (Docker Desktop on
macOS/Windows, Docker Engine on Linux):

```sh
docker compose -f docker-compose.yml up --build
# or, no local build, from the published multi-arch image:
docker compose -f docker-compose.published.yml up
```

See [`docs/observability/`](docs/observability/) to add the metrics dashboards.

## Troubleshooting (macOS/Linux/WSL)

- **`java: command not found`** — finish Step 1 and open a new terminal.
- **`llama-server` not found** — finish Step 3, or set `llama.manage-server=false` to use an external one.
- **`permission denied: ./run.sh`** — run `chmod +x *.sh scripts/*.sh` once.
- **macOS "cannot be opened because the developer cannot be verified"** for `llama-server` — clear the
  quarantine attribute: `xattr -d com.apple.quarantine /path/to/llama-server`.
- **WSL** — run everything inside the WSL shell; reach the app from Windows at `http://localhost:8080`.
