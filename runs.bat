@echo off
echo Concurrency status (limit / active / queued):
curl -s http://localhost:8080/runs
echo.
