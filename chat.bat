@echo off
setlocal
if "%~2"=="" (
  echo Usage: chat.bat SESSION_ID "your message"
  echo Example: chat.bat work1 "View pom.xml and tell me the dependencies"
  echo Then continue the same conversation:
  echo          chat.bat work1 "Now add a comment above the jsoup dependency"
  exit /b 1
)
curl -s -X POST http://localhost:8080/chat -H "Content-Type: application/json" -d "{\"sessionId\":\"%~1\",\"message\":\"%~2\"}"
echo.
endlocal
