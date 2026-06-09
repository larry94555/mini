@echo off
setlocal
if "%~2"=="" ( echo Usage: steer.bat SESSION_ID "guidance for the running agent" & exit /b 1 )
curl -s -X POST http://localhost:8080/steer -H "Content-Type: application/json" -d "{\"sessionId\":\"%~1\",\"message\":\"%~2\"}"
echo.
endlocal
