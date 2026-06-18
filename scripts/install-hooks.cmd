@echo off
rem Point git at the repo's hooks (one time).
cd /d "%~dp0.."
git config core.hooksPath .githooks
echo Installed git hooks (core.hooksPath=.githooks).
