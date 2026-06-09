@echo off
setlocal
if "%~1"=="" (
  echo Usage: ask.bat "your question"
  echo Example: ask.bat "What is the current top story on FoxNews.com?"
  exit /b 1
)
echo Asking: %~1
curl -s -X POST http://localhost:8080/ask -H "Content-Type: application/json" -d "{\"question\":\"%~1\"}"
echo.
endlocal
