@echo off
cd /d "%~dp0"
set "NODE_EXE=node"
where node >nul 2>nul
if errorlevel 1 set "NODE_EXE=D:\develop\Nodejs\node.exe"
if not exist "%NODE_EXE%" (
  where node >nul 2>nul
  if errorlevel 1 (
    echo Node.js 20 or later is required. Install it under D:\develop\Nodejs or add it to PATH.
    pause
    exit /b 1
  )
)
if not exist "node_modules\vue\dist\vue.esm-browser.prod.js" (
  echo Vue is not installed. Run npm install first.
  pause
  exit /b 1
)
echo Open http://127.0.0.1:4173 in your browser.
"%NODE_EXE%" server.mjs
pause
