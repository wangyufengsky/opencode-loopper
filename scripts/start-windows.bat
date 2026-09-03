@echo off
setlocal EnableExtensions DisableDelayedExpansion

if /I "%~1"=="--wait-browser" goto wait_browser
if /I "%~1"=="--validate" (
  call :discover_opencode
  if errorlevel 1 (
    echo [Loopper] ERROR: Windows OpenCode discovery validation failed. 1>&2
    exit /b 1
  )
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

if not defined LOOPPER_DATA_DIR set "LOOPPER_DATA_DIR=%APP_HOME%\data"
set "STARTUP_OVERRIDES=%LOOPPER_DATA_DIR%\config\startup-overrides.properties"
if exist "%STARTUP_OVERRIDES%" (
  powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$rules=@{SERVER_PORT='^\d+$';LOOPPER_OPEN_BROWSER='^(true|false)$';LOOPPER_ALLOWED_ROOT='^.*$';LOOPPER_MONITOR_DELAY='^\d+(ms|s|m|h)$';LOOPPER_DESIGNER_MONITOR_DELAY='^\d+(ms|s|m|h)$';LOOPPER_ABORT_CLEANUP_ATTEMPTS='^\d+$';LOOPPER_OPENCODE_MODE='^(managed|auto|http)$';OPENCODE_BASE_URL='^https?://\S+$';OPENCODE_EXECUTABLE='^.+$';OPENCODE_MODEL='^.+$';OPENCODE_ENABLE_QUESTION_TOOL='^(true|false)$';LOOPPER_OPENCODE_CONNECT_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_OPENCODE_REQUEST_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_OPENCODE_STARTUP_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_MAX_STAGE_ATTEMPTS='^\d+$';LOOPPER_MAX_TASK_ATTEMPTS='^\d+$';LOOPPER_SESSION_ERROR_LIMIT='^\d+$';LOOPPER_MAX_DURATION='^\d+(ms|s|m|h)$';LOOPPER_ATTEMPT_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_VERIFIER_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_DESIGNER_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_RETRY_RATE_LIMIT_BASE='^\d+(ms|s|m|h)$';LOOPPER_RETRY_RATE_LIMIT_MAX='^\d+(ms|s|m|h)$';LOOPPER_RETRY_SESSION_BASE='^\d+(ms|s|m|h)$';LOOPPER_RETRY_SESSION_MAX='^\d+(ms|s|m|h)$';LOOPPER_RETRY_VERIFICATION_BASE='^\d+(ms|s|m|h)$';LOOPPER_RETRY_VERIFICATION_MAX='^\d+(ms|s|m|h)$';LOOPPER_PUBLICATION_HTTP_WEB_HOSTS='^.*$';LOOPPER_GITLAB_HOST='^.+$';LOOPPER_GITLAB_API_BASE_URL='^https?://\S+$';LOOPPER_GITLAB_CONNECT_TIMEOUT='^\d+(ms|s|m|h)$';LOOPPER_GITLAB_REQUEST_TIMEOUT='^\d+(ms|s|m|h)$'}; foreach($line in Get-Content -LiteralPath $env:STARTUP_OVERRIDES -Encoding UTF8){if(!$line -or $line.StartsWith('#')){continue};$parts=$line.Split('=',2);if($parts.Count-ne 2){Write-Error ('Invalid startup setting line: '+$line);exit 2};if(!$rules.ContainsKey($parts[0])){Write-Warning ('Ignoring unknown startup setting: '+$parts[0]);continue};if($parts[1] -notmatch $rules[$parts[0]]){Write-Error ('Invalid startup setting value: '+$parts[0]);exit 2}}"
  if errorlevel 1 exit /b 1
  for /f "usebackq tokens=1,* delims==" %%K in ("%STARTUP_OVERRIDES%") do (
    if /I "%%K"=="SERVER_PORT" if not defined SERVER_PORT set "SERVER_PORT=%%L"
    if /I "%%K"=="LOOPPER_OPEN_BROWSER" if not defined LOOPPER_OPEN_BROWSER set "LOOPPER_OPEN_BROWSER=%%L"
    if /I "%%K"=="LOOPPER_ALLOWED_ROOT" if not defined LOOPPER_ALLOWED_ROOT set "LOOPPER_ALLOWED_ROOT=%%L"
    if /I "%%K"=="LOOPPER_MONITOR_DELAY" if not defined LOOPPER_MONITOR_DELAY set "LOOPPER_MONITOR_DELAY=%%L"
    if /I "%%K"=="LOOPPER_DESIGNER_MONITOR_DELAY" if not defined LOOPPER_DESIGNER_MONITOR_DELAY set "LOOPPER_DESIGNER_MONITOR_DELAY=%%L"
    if /I "%%K"=="LOOPPER_ABORT_CLEANUP_ATTEMPTS" if not defined LOOPPER_ABORT_CLEANUP_ATTEMPTS set "LOOPPER_ABORT_CLEANUP_ATTEMPTS=%%L"
    if /I "%%K"=="LOOPPER_OPENCODE_MODE" if not defined LOOPPER_OPENCODE_MODE set "LOOPPER_OPENCODE_MODE=%%L"
    if /I "%%K"=="OPENCODE_BASE_URL" if not defined OPENCODE_BASE_URL set "OPENCODE_BASE_URL=%%L"
    if /I "%%K"=="OPENCODE_EXECUTABLE" if not defined OPENCODE_EXECUTABLE set "OPENCODE_EXECUTABLE=%%L"
    if /I "%%K"=="OPENCODE_MODEL" if not defined OPENCODE_MODEL set "OPENCODE_MODEL=%%L"
    if /I "%%K"=="OPENCODE_ENABLE_QUESTION_TOOL" if not defined OPENCODE_ENABLE_QUESTION_TOOL set "OPENCODE_ENABLE_QUESTION_TOOL=%%L"
    if /I "%%K"=="LOOPPER_OPENCODE_CONNECT_TIMEOUT" if not defined LOOPPER_OPENCODE_CONNECT_TIMEOUT set "LOOPPER_OPENCODE_CONNECT_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_OPENCODE_REQUEST_TIMEOUT" if not defined LOOPPER_OPENCODE_REQUEST_TIMEOUT set "LOOPPER_OPENCODE_REQUEST_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_OPENCODE_STARTUP_TIMEOUT" if not defined LOOPPER_OPENCODE_STARTUP_TIMEOUT set "LOOPPER_OPENCODE_STARTUP_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_MAX_STAGE_ATTEMPTS" if not defined LOOPPER_MAX_STAGE_ATTEMPTS set "LOOPPER_MAX_STAGE_ATTEMPTS=%%L"
    if /I "%%K"=="LOOPPER_MAX_TASK_ATTEMPTS" if not defined LOOPPER_MAX_TASK_ATTEMPTS set "LOOPPER_MAX_TASK_ATTEMPTS=%%L"
    if /I "%%K"=="LOOPPER_SESSION_ERROR_LIMIT" if not defined LOOPPER_SESSION_ERROR_LIMIT set "LOOPPER_SESSION_ERROR_LIMIT=%%L"
    if /I "%%K"=="LOOPPER_MAX_DURATION" if not defined LOOPPER_MAX_DURATION set "LOOPPER_MAX_DURATION=%%L"
    if /I "%%K"=="LOOPPER_ATTEMPT_TIMEOUT" if not defined LOOPPER_ATTEMPT_TIMEOUT set "LOOPPER_ATTEMPT_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_VERIFIER_TIMEOUT" if not defined LOOPPER_VERIFIER_TIMEOUT set "LOOPPER_VERIFIER_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_DESIGNER_TIMEOUT" if not defined LOOPPER_DESIGNER_TIMEOUT set "LOOPPER_DESIGNER_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_RETRY_RATE_LIMIT_BASE" if not defined LOOPPER_RETRY_RATE_LIMIT_BASE set "LOOPPER_RETRY_RATE_LIMIT_BASE=%%L"
    if /I "%%K"=="LOOPPER_RETRY_RATE_LIMIT_MAX" if not defined LOOPPER_RETRY_RATE_LIMIT_MAX set "LOOPPER_RETRY_RATE_LIMIT_MAX=%%L"
    if /I "%%K"=="LOOPPER_RETRY_SESSION_BASE" if not defined LOOPPER_RETRY_SESSION_BASE set "LOOPPER_RETRY_SESSION_BASE=%%L"
    if /I "%%K"=="LOOPPER_RETRY_SESSION_MAX" if not defined LOOPPER_RETRY_SESSION_MAX set "LOOPPER_RETRY_SESSION_MAX=%%L"
    if /I "%%K"=="LOOPPER_RETRY_VERIFICATION_BASE" if not defined LOOPPER_RETRY_VERIFICATION_BASE set "LOOPPER_RETRY_VERIFICATION_BASE=%%L"
    if /I "%%K"=="LOOPPER_RETRY_VERIFICATION_MAX" if not defined LOOPPER_RETRY_VERIFICATION_MAX set "LOOPPER_RETRY_VERIFICATION_MAX=%%L"
    if /I "%%K"=="LOOPPER_PUBLICATION_HTTP_WEB_HOSTS" if not defined LOOPPER_PUBLICATION_HTTP_WEB_HOSTS set "LOOPPER_PUBLICATION_HTTP_WEB_HOSTS=%%L"
    if /I "%%K"=="LOOPPER_GITLAB_HOST" if not defined LOOPPER_GITLAB_HOST set "LOOPPER_GITLAB_HOST=%%L"
    if /I "%%K"=="LOOPPER_GITLAB_API_BASE_URL" if not defined LOOPPER_GITLAB_API_BASE_URL set "LOOPPER_GITLAB_API_BASE_URL=%%L"
    if /I "%%K"=="LOOPPER_GITLAB_CONNECT_TIMEOUT" if not defined LOOPPER_GITLAB_CONNECT_TIMEOUT set "LOOPPER_GITLAB_CONNECT_TIMEOUT=%%L"
    if /I "%%K"=="LOOPPER_GITLAB_REQUEST_TIMEOUT" if not defined LOOPPER_GITLAB_REQUEST_TIMEOUT set "LOOPPER_GITLAB_REQUEST_TIMEOUT=%%L"
  )
)

if not defined OPENCODE_ENABLE_QUESTION_TOOL set "OPENCODE_ENABLE_QUESTION_TOOL=true"
if not defined LOOPPER_RETRY_RATE_LIMIT_BASE set "LOOPPER_RETRY_RATE_LIMIT_BASE=60s"
if not defined LOOPPER_RETRY_RATE_LIMIT_MAX set "LOOPPER_RETRY_RATE_LIMIT_MAX=300s"
if not defined LOOPPER_RETRY_SESSION_BASE set "LOOPPER_RETRY_SESSION_BASE=10s"
if not defined LOOPPER_RETRY_SESSION_MAX set "LOOPPER_RETRY_SESSION_MAX=60s"
if not defined LOOPPER_RETRY_VERIFICATION_BASE set "LOOPPER_RETRY_VERIFICATION_BASE=5s"
if not defined LOOPPER_RETRY_VERIFICATION_MAX set "LOOPPER_RETRY_VERIFICATION_MAX=30s"
if not defined LOOPPER_OPENCODE_MODE set "LOOPPER_OPENCODE_MODE=managed"

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
if exist "%APP_HOME%\target\opencode-loopper-0.3.39.jar" (
  set "JAR_PATH=%APP_HOME%\target\opencode-loopper-0.3.39.jar"
  goto jar_ready
)
if exist "%APP_HOME%\opencode-loopper-0.3.39.jar" (
  set "JAR_PATH=%APP_HOME%\opencode-loopper-0.3.39.jar"
  goto jar_ready
)
goto jar_missing

:jar_from_environment
set "JAR_PATH=%LOOPPER_JAR_PATH%"

:jar_ready
if not exist "%JAR_PATH%" goto jar_missing

set "OPENCODE_BASE_URL_EXPLICIT=false"
set "OPENCODE_BASE_URL_SOURCE=environment"
if defined OPENCODE_BASE_URL set "OPENCODE_BASE_URL_EXPLICIT=true"
if not defined LOOPPER_DESIGNER_TIMEOUT set "LOOPPER_DESIGNER_TIMEOUT=30m"
if not defined LOOPPER_PUBLICATION_HTTP_WEB_HOSTS set "LOOPPER_PUBLICATION_HTTP_WEB_HOSTS=gitlab.spdb.com"
if not defined LOOPPER_GITLAB_HOST set "LOOPPER_GITLAB_HOST=gitlab.spdb.com"
if not defined LOOPPER_GITLAB_API_BASE_URL set "LOOPPER_GITLAB_API_BASE_URL=http://gitlab.spdb.com/api/v4"
if not defined SERVER_PORT set "SERVER_PORT=8080"
if not exist "%LOOPPER_DATA_DIR%" mkdir "%LOOPPER_DATA_DIR%"
if errorlevel 1 goto data_dir_failed

set "APP_URL=http://127.0.0.1:%SERVER_PORT%"

echo [Loopper] Java source: %JAVA_SOURCE%
echo [Loopper] Java: %JAVA_VERSION_LINE%
echo [Loopper] JAR: %JAR_PATH%
echo [Loopper] Data directory: %LOOPPER_DATA_DIR%
echo [Loopper] Designer timeout: %LOOPPER_DESIGNER_TIMEOUT%
echo [Loopper] Page: %APP_URL%

if /I "%LOOPPER_OPENCODE_MODE%"=="fake" goto start_loopper
if /I "%LOOPPER_OPENCODE_MODE%"=="managed" goto managed_opencode
where curl.exe >nul 2>&1
if errorlevel 1 goto curl_missing
if /I "%OPENCODE_BASE_URL_EXPLICIT%"=="true" goto check_explicit_opencode

call :discover_opencode
if errorlevel 1 goto opencode_discovery_failed
if defined OPENCODE_BASE_URL (
  set "OPENCODE_BASE_URL_SOURCE=running opencode process"
  if not defined LOOPPER_OPENCODE_MODE set "LOOPPER_OPENCODE_MODE=http"
  goto opencode_ready
)

if /I "%LOOPPER_OPENCODE_MODE%"=="http" goto opencode_not_found_http
echo [Loopper] OpenCode: no reusable endpoint was found; auto mode will start it on a dynamic loopback port.
goto start_loopper

:managed_opencode
echo [Loopper] OpenCode: managed mode will start an isolated OpenCode process on a dynamic loopback port with a fresh internal MCP generation.
goto start_loopper

:check_explicit_opencode
if not defined LOOPPER_OPENCODE_MODE set "LOOPPER_OPENCODE_MODE=http"
call :opencode_health
if not errorlevel 1 goto opencode_ready
goto custom_opencode_offline

:opencode_ready
if defined OPENCODE_BASE_URL echo [Loopper] OpenCode: %OPENCODE_BASE_URL% ^(source: %OPENCODE_BASE_URL_SOURCE%^)
echo [Loopper] OpenCode health check passed.

:start_loopper
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

:discover_opencode
set "DISCOVERED_OPENCODE_BASE_URL="
set "OPENCODE_DISCOVERY_VALID="
for /f "usebackq delims=" %%U in (`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$ErrorActionPreference='SilentlyContinue'; $headers=@{}; if($env:OPENCODE_USERNAME){ $pair=$env:OPENCODE_USERNAME + ':' + $env:OPENCODE_PASSWORD; $headers.Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }; $result=Get-CimInstance Win32_Process ^| Where-Object { $_.CommandLine -and $_.CommandLine -match '(?i)opencode' -and $_.CommandLine -match '(?i)\sserve\s' } ^| Sort-Object CreationDate -Descending ^| ForEach-Object { if($_.CommandLine -match '(?i)--port[= ]+(\d{1,5})'){ $port=[int]$Matches[1]; if($port -ge 1 -and $port -le 65535){ $url='http://127.0.0.1:' + $port; try { $health=Invoke-RestMethod -TimeoutSec 3 -Headers $headers -Uri ($url + '/global/health'); if($health.healthy -eq $true){ $url } } catch {} } } } ^| Select-Object -First 1; if($result){ $result }; 'DISCOVERY_OK'" 2^>nul`) do call :capture_discovery_line "%%U"
if not defined OPENCODE_DISCOVERY_VALID exit /b 1
if defined DISCOVERED_OPENCODE_BASE_URL set "OPENCODE_BASE_URL=%DISCOVERED_OPENCODE_BASE_URL%"
exit /b 0

:capture_discovery_line
if /I "%~1"=="DISCOVERY_OK" (
  set "OPENCODE_DISCOVERY_VALID=true"
  exit /b 0
)
if not defined DISCOVERED_OPENCODE_BASE_URL set "DISCOVERED_OPENCODE_BASE_URL=%~1"
exit /b 0

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
echo [Loopper] ERROR: opencode-loopper-0.3.39.jar was not found under "%APP_HOME%". Put the release JAR beside this script or set LOOPPER_JAR_PATH. 1>&2
exit /b 1

:data_dir_failed
echo [Loopper] ERROR: Cannot create data directory: %LOOPPER_DATA_DIR% 1>&2
exit /b 1

:curl_missing
echo [Loopper] ERROR: curl.exe is required for the OpenCode health check. Install it or add it to PATH. 1>&2
exit /b 1

:custom_opencode_offline
echo [Loopper] ERROR: The configured OpenCode service is offline: %OPENCODE_BASE_URL% 1>&2
echo [Loopper] Start that service first, or remove OPENCODE_BASE_URL to enable process discovery and dynamic auto startup. 1>&2
exit /b 1

:opencode_not_found_http
echo [Loopper] ERROR: LOOPPER_OPENCODE_MODE=http was requested, but no healthy running OpenCode process was discovered. 1>&2
echo [Loopper] Set OPENCODE_BASE_URL explicitly, or use auto mode for dynamic loopback startup. 1>&2
exit /b 1

:opencode_discovery_failed
echo [Loopper] ERROR: Failed to inspect current Windows processes for an OpenCode endpoint. 1>&2
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
