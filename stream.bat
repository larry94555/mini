@echo off
setlocal
if "%~2"=="" (
  echo Usage: stream.bat SESSION_ID "your message"
  echo Streams the run as Server-Sent Events ^(token/log/answer^) live in this terminal.
  echo Interrupt it from another terminal with: interrupt.bat SESSION_ID
  exit /b 1
)
curl -N -s -X POST http://localhost:8080/chat/stream -H "Content-Type: application/json" -d "{\"sessionId\":\"%~1\",\"message\":\"%~2\"}"
echo.
endlocal
