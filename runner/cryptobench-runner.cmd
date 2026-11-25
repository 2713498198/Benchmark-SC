@echo off
setlocal
set "RUNNER_HOME=%~dp0"
if exist "%RUNNER_HOME%node.exe" (
  "%RUNNER_HOME%node.exe" "%RUNNER_HOME%src\cli.mjs" %*
) else (
  node "%RUNNER_HOME%src\cli.mjs" %*
)
exit /b %ERRORLEVEL%
