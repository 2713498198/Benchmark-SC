@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-v4-judge.ps1" %*
exit /b %ERRORLEVEL%
