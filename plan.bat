@echo off
setlocal
if "%~1"=="" (
  echo Usage: plan.bat "your request"
  echo Runs the request in PLAN mode: the agent proposes edits/commands but executes nothing.
  exit /b 1
)
curl -s -X POST http://localhost:8080/ask -H "Content-Type: application/json" -d "{\"question\":\"%~1\",\"mode\":\"plan\"}"
echo.
endlocal
