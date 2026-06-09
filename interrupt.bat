@echo off
setlocal
if "%~1"=="" ( echo Usage: interrupt.bat SESSION_ID & exit /b 1 )
echo Requesting interrupt of session %~1 ...
curl -s -X POST http://localhost:8080/interrupt -H "Content-Type: application/json" -d "{\"sessionId\":\"%~1\"}"
echo.
endlocal
