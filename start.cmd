@echo off
setlocal
cd /d "%~dp0"
if exist "D:\develop\jdk\bin\javac.exe" (
  set "JAVA_HOME=D:\develop\jdk"
  set "PATH=D:\develop\jdk\bin;%PATH%"
)
set "MAVEN_CMD=mvn.cmd"
where mvn.cmd >nul 2>nul
if errorlevel 1 set "MAVEN_CMD=D:\develop\apache-maven-3.9.15\bin\mvn.cmd"
where javac >nul 2>nul
if errorlevel 1 (
  echo JDK 21 is required. Set JAVA_HOME or install it under D:\develop\jdk.
  pause
  exit /b 1
)
call "%MAVEN_CMD%" -v >nul 2>nul
if errorlevel 1 (
  echo Maven 3.6.3 or later is required. Add it to PATH or install under D:\develop\apache-maven-3.9.15.
  pause
  exit /b 1
)
echo Starting CryptoBench with Spring Boot at http://127.0.0.1:4173
echo The first launch downloads Maven dependencies into .mvn\repository on drive D.
call "%MAVEN_CMD%" spring-boot:run
if errorlevel 1 (
  echo Startup failed. Review the error above.
  pause
  exit /b 1
)
endlocal
