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
