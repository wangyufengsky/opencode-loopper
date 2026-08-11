@echo off
setlocal EnableExtensions DisableDelayedExpansion

if /I "%~1"=="--wait-browser" goto wait_browser
if /I "%~1"=="--validate" (
  cmd /d /c exit 7
  call :start_background "Loopper Start Validation" "%ComSpec%" /d /c exit 0
  if errorlevel 1 (
    echo [Loopper] ERROR: Windows background-start validation failed. 1>&2
    exit /b 1
  )
  echo [Loopper] Windows startup script validation passed.
  exit /b 0
)

set "SCRIPT_DIR=%~dp0"
if exist "%SCRIPT_DIR%..\pom.xml" (
  for %%I in ("%SCRIPT_DIR%..") do set "APP_HOME=%%~fI"
) else (
  for %%I in ("%SCRIPT_DIR%.") do set "APP_HOME=%%~fI"
)

if defined LOOPPER_JAVA_HOME goto java_from_loopper
if defined JAVA_HOME goto java_from_java_home
goto java_from_path

:java_from_loopper
set "JAVA_BIN=%LOOPPER_JAVA_HOME%\bin\java.exe"
set "JAVA_SOURCE=LOOPPER_JAVA_HOME"
goto java_ready

:java_from_java_home
set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
set "JAVA_SOURCE=JAVA_HOME"
goto java_ready

:java_from_path
set "JAVA_BIN="
for /f "delims=" %%I in ('where java.exe 2^>nul') do if not defined JAVA_BIN set "JAVA_BIN=%%I"
set "JAVA_SOURCE=PATH"

:java_ready
if not defined JAVA_BIN goto java_missing
if not exist "%JAVA_BIN%" goto java_missing

set "JAVA_VERSION_LINE="
for /f "delims=" %%V in ('"%JAVA_BIN%" -version 2^>^&1') do if not defined JAVA_VERSION_LINE set "JAVA_VERSION_LINE=%%V"
if not defined JAVA_VERSION_LINE goto java_version_unknown
for /f "tokens=3" %%V in ("%JAVA_VERSION_LINE%") do set "JAVA_VERSION=%%~V"
if not defined JAVA_VERSION goto java_version_unknown
for /f "tokens=1,2 delims=." %%M in ("%JAVA_VERSION%") do (
  set "JAVA_MAJOR=%%M"
  if "%%M"=="1" set "JAVA_MAJOR=%%N"
)
set /a JAVA_MAJOR_NUMBER=%JAVA_MAJOR% 2>nul
if errorlevel 1 goto java_version_unknown
if %JAVA_MAJOR_NUMBER% LSS 21 goto java_too_old

if defined LOOPPER_JAR_PATH goto jar_from_environment
if exist "%APP_HOME%\target\opencode-loopper-0.1.14.jar" (
  set "JAR_PATH=%APP_HOME%\target\opencode-loopper-0.1.14.jar"
  goto jar_ready
)
if exist "%APP_HOME%\opencode-loopper-0.1.14.jar" (
  set "JAR_PATH=%APP_HOME%\opencode-loopper-0.1.14.jar"
  goto jar_ready
)
goto jar_missing

:jar_from_environment
set "JAR_PATH=%LOOPPER_JAR_PATH%"

:jar_ready
if not exist "%JAR_PATH%" goto jar_missing

if not defined LOOPPER_DATA_DIR set "LOOPPER_DATA_DIR=%APP_HOME%\data"
if not defined LOOPPER_OPENCODE_MODE set "LOOPPER_OPENCODE_MODE=http"
if not defined OPENCODE_BASE_URL set "OPENCODE_BASE_URL=http://127.0.0.1:4096"
if not defined LOOPPER_DESIGNER_TIMEOUT set "LOOPPER_DESIGNER_TIMEOUT=30m"
if not defined SERVER_PORT set "SERVER_PORT=8080"
if not exist "%LOOPPER_DATA_DIR%" mkdir "%LOOPPER_DATA_DIR%"
if errorlevel 1 goto data_dir_failed

set "APP_URL=http://127.0.0.1:%SERVER_PORT%"

echo [Loopper] Java source: %JAVA_SOURCE%
echo [Loopper] Java: %JAVA_VERSION_LINE%
echo [Loopper] JAR: %JAR_PATH%
echo [Loopper] Data directory: %LOOPPER_DATA_DIR%
echo [Loopper] OpenCode: %OPENCODE_BASE_URL%
echo [Loopper] Designer timeout: %LOOPPER_DESIGNER_TIMEOUT%
echo [Loopper] Page: %APP_URL%

where curl.exe >nul 2>&1
if errorlevel 1 goto curl_missing

call :opencode_health
if not errorlevel 1 goto opencode_ready

if /I not "%OPENCODE_BASE_URL%"=="http://127.0.0.1:4096" goto custom_opencode_offline

set "OPENCODE_BIN="
if defined OPENCODE_EXECUTABLE set "OPENCODE_BIN=%OPENCODE_EXECUTABLE%"
if not defined OPENCODE_BIN for /f "delims=" %%I in ('where opencode.exe 2^>nul') do if not defined OPENCODE_BIN set "OPENCODE_BIN=%%I"
if not defined OPENCODE_BIN for /f "delims=" %%I in ('where opencode.cmd 2^>nul') do if not defined OPENCODE_BIN set "OPENCODE_BIN=%%I"
if not defined OPENCODE_BIN for /f "delims=" %%I in ('where opencode 2^>nul') do if not defined OPENCODE_BIN set "OPENCODE_BIN=%%I"
if not defined OPENCODE_BIN goto opencode_missing
if not exist "%OPENCODE_BIN%" goto opencode_missing

if defined OPENCODE_USERNAME set "OPENCODE_SERVER_USERNAME=%OPENCODE_USERNAME%"
if defined OPENCODE_PASSWORD set "OPENCODE_SERVER_PASSWORD=%OPENCODE_PASSWORD%"

echo [Loopper] OpenCode is offline; starting a loopback server with: %OPENCODE_BIN%
rem START is asynchronous and can preserve an earlier nonzero ERRORLEVEL on success.
rem The health endpoint below is the authoritative startup result.
call :start_background "Loopper OpenCode Server" "%OPENCODE_BIN%" serve --hostname 127.0.0.1 --port 4096

for /L %%I in (1,1,30) do (
  call :opencode_health
  if not errorlevel 1 goto opencode_ready
  >nul 2>&1 ping 127.0.0.1 -n 2
)
goto opencode_start_timeout

:opencode_ready
echo [Loopper] OpenCode health check passed.
if /I not "%LOOPPER_OPEN_BROWSER%"=="false" start "Loopper Browser Waiter" /B "%ComSpec%" /d /s /c ""%~f0" --wait-browser "%APP_URL%""
echo [Loopper] Starting Loopper. Press Ctrl+C to stop it.
"%JAVA_BIN%" "-Djava.awt.headless=false" -jar "%JAR_PATH%" %*
set "LOOPPER_EXIT_CODE=%ERRORLEVEL%"
echo [Loopper] Loopper exited with code %LOOPPER_EXIT_CODE%.
exit /b %LOOPPER_EXIT_CODE%

:opencode_health
if defined OPENCODE_USERNAME (
  curl.exe --fail --silent --show-error --max-time 3 --user "%OPENCODE_USERNAME%:%OPENCODE_PASSWORD%" "%OPENCODE_BASE_URL%/global/health" >nul 2>&1
) else (
  curl.exe --fail --silent --show-error --max-time 3 "%OPENCODE_BASE_URL%/global/health" >nul 2>&1
)
exit /b %ERRORLEVEL%

:java_missing
echo [Loopper] ERROR: Java was not found. Set LOOPPER_JAVA_HOME to a JDK 21 directory, or configure JAVA_HOME/PATH. 1>&2
exit /b 1

:java_version_unknown
echo [Loopper] ERROR: Cannot parse the Java version from: %JAVA_VERSION_LINE% 1>&2
exit /b 1

:java_too_old
echo [Loopper] ERROR: JDK 21 or newer is required. Current version: %JAVA_VERSION_LINE% 1>&2
exit /b 1

:jar_missing
echo [Loopper] ERROR: opencode-loopper-0.1.14.jar was not found under "%APP_HOME%". Put the release JAR beside this script or set LOOPPER_JAR_PATH. 1>&2
exit /b 1

:data_dir_failed
echo [Loopper] ERROR: Cannot create data directory: %LOOPPER_DATA_DIR% 1>&2
exit /b 1

:curl_missing
echo [Loopper] ERROR: curl.exe is required for the OpenCode health check. Install it or add it to PATH. 1>&2
exit /b 1

:custom_opencode_offline
echo [Loopper] ERROR: The configured OpenCode service is offline: %OPENCODE_BASE_URL% 1>&2
echo [Loopper] Start that service first, or remove OPENCODE_BASE_URL to let this script start 127.0.0.1:4096. 1>&2
exit /b 1

:opencode_missing
echo [Loopper] ERROR: OpenCode is offline and the opencode command was not found. 1>&2
echo [Loopper] Install OpenCode or set OPENCODE_EXECUTABLE to opencode.exe/opencode.cmd. 1>&2
exit /b 1

:opencode_start_timeout
echo [Loopper] ERROR: OpenCode did not become healthy within 30 seconds. Check whether port 4096 is occupied. 1>&2
exit /b 1

:wait_browser
set "WAIT_URL=%~2"
for /L %%I in (1,1,60) do (
  curl.exe --fail --silent --max-time 1 "%WAIT_URL%/actuator/health" 2>nul | findstr /I /C:"UP" >nul
  if not errorlevel 1 goto browser_ready
  >nul 2>&1 ping 127.0.0.1 -n 2
)
echo [Loopper] Loopper did not become healthy within 60 seconds. Check the startup log. 1>&2
exit /b 1

:browser_ready
start "" "%WAIT_URL%"
exit /b 0

:start_background
start %*
exit /b 0
