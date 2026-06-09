@echo off
setlocal
echo Rewinding the most recent file edit...
curl -s -X POST http://localhost:8080/rewind
echo.
echo (To see available rewind points: open http://localhost:8080/checkpoints in a browser)
endlocal
