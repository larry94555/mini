@echo off
echo Requesting interrupt of the current run...
curl -s -X POST http://localhost:8080/interrupt
echo.
