@echo off
setlocal
cd /d "%~dp0"
set "MVN_VERSION=3.9.9"

echo ============================================
echo   imini launcher
echo ============================================
echo.

echo [1/4] Checking for Java...
where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo   [ERROR] Java was not found on this computer.
  echo   Install Java 17 or newer, then run this again.
  echo   Easiest way: open PowerShell and run:
  echo       winget install EclipseAdoptium.Temurin.17.JDK
  echo   Then close and reopen this window.
  echo.
  pause
  exit /b 1
)
java -version
echo.

echo [2/4] Checking for llama-server.exe...
where llama-server.exe >nul 2>nul
if not errorlevel 1 (
  echo   Found llama-server.exe on your PATH. Good.
) else (
  if exist "%~dp0llama-server.exe" (
    echo   Found llama-server.exe in this folder. Good.
  ) else (
    echo   [WARNING] llama-server.exe was not found on your PATH or in this folder.
    echo   The app will still start, but it cannot run the AI model until you add it.
    echo   See the install instructions ^(step 2^) for how to get it.
  )
)
echo.

echo [3/4] Locating Maven ^(the build tool^)...
set "MVN_CMD="
where mvn >nul 2>nul
if not errorlevel 1 set "MVN_CMD=mvn"
if defined MVN_CMD goto :have_maven

set "LOCAL_MVN=%~dp0.maven\apache-maven-%MVN_VERSION%\bin\mvn.cmd"
if exist "%LOCAL_MVN%" goto :use_local

echo   Maven not found - downloading a local copy now ^(one time, about 10 MB^)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\get-maven.ps1" -Version %MVN_VERSION% -Dest "%~dp0.maven"
if errorlevel 1 (
  echo.
  echo   [ERROR] Could not download Maven automatically.
  echo   Install it manually:  winget install Apache.Maven
  echo.
  pause
  exit /b 1
)
:use_local
set "MVN_CMD=%LOCAL_MVN%"
:have_maven
echo   Using Maven: %MVN_CMD%
echo.

echo [4/4] Building and starting imini...
echo.
echo   NOTE: the first run is slow and may look quiet.
echo     - It downloads Java libraries ^(a few minutes^).
echo     - Then it downloads the AI model, about 2 GB ^(can be 5-20 minutes^).
echo       Model download progress is written to the file: llama-server.log
echo   When you see the lines:
echo       "llama-server is ready."
echo       "Started MiniAgentApplication ..."
echo   ...the app is running at http://localhost:8080  -- leave this window open.
echo.
echo   Press Ctrl+C to stop it later.
echo.
call "%MVN_CMD%" spring-boot:run

echo.
echo imini has stopped.
pause
endlocal
