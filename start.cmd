@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" if "%~1"=="" pause
exit /b %RESULT%
