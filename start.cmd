@echo off
setlocal
cd /d "%~dp0"
if exist "D:\develop\jdk\bin\javac.exe" (
  set "JAVA_HOME=D:\develop\jdk"
  set "PATH=D:\develop\jdk\bin;%PATH%"
)
set "MAVEN_CMD="
for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
if not defined MAVEN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MAVEN_CMD if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MAVEN_CMD=%M2_HOME%\bin\mvn.cmd"
if not defined MAVEN_CMD for /d %%M in ("%~dp0.tools\apache-maven-*" "D:\develop\apache-maven-*") do if exist "%%~M\bin\mvn.cmd" set "MAVEN_CMD=%%~M\bin\mvn.cmd"
rem VS Code's Oracle Java extension bundles Maven; allow extension upgrades.
if not defined MAVEN_CMD for /d %%E in ("%USERPROFILE%\.vscode\extensions\oracle.oracle-java-*") do if exist "%%~E\nbcode\java\maven\bin\mvn.cmd" set "MAVEN_CMD=%%~E\nbcode\java\maven\bin\mvn.cmd"
if not defined MAVEN_CMD (
  echo Maven was not found. Add Maven 3.6.3 or later to PATH,
  echo set MAVEN_HOME or M2_HOME, or install the Oracle Java extension for VS Code.
  pause
  exit /b 1
)
where javac >nul 2>nul
if errorlevel 1 (
  echo JDK 21 is required. Set JAVA_HOME or install it under D:\develop\jdk.
  pause
  exit /b 1
)
call "%MAVEN_CMD%" -v >nul 2>nul
if errorlevel 1 (
  echo Maven could not run: "%MAVEN_CMD%"
  echo Check JAVA_HOME and install Maven 3.6.3 or later.
  pause
  exit /b 1
)
echo Using Maven: "%MAVEN_CMD%"
echo Starting CryptoBench with Spring Boot at http://127.0.0.1:4173
echo The first launch downloads Maven dependencies into .mvn\repository on drive D.
call "%MAVEN_CMD%" spring-boot:run
if errorlevel 1 (
  echo Startup failed. Review the error above.
  pause
  exit /b 1
)
endlocal
