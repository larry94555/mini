@echo off
setlocal
if "%~1"=="" ( echo Usage: steer.bat "guidance for the running agent" & exit /b 1 )
curl -s -X POST http://localhost:8080/steer -H "Content-Type: application/json" -d "{\"message\":\"%~1\"}"
echo.
endlocal
