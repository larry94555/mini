@echo off
REM Run the behavioral eval suite against a running imini (see evals/cases.json).
powershell -ExecutionPolicy Bypass -File "%~dp0scripts\run-evals.ps1" %*
