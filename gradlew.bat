@echo off
setlocal
set "VERSION=9.6.0"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "DIST_ROOT=%GRADLE_USER_HOME%\wrapper\dists\gradle-%VERSION%-bin\pocketvpnbridge"
set "GRADLE_HOME=%DIST_ROOT%\gradle-%VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
  set "TMP=%TEMP%\pocketvpn-gradle-%VERSION%-%RANDOM%"
  mkdir "%TMP%" 2>nul
  mkdir "%DIST_ROOT%" 2>nul
  echo Downloading Gradle %VERSION% from services.gradle.org...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%TEMP%\pocketvpn-gradle-%VERSION%.zip'; Expand-Archive -Path '%TEMP%\pocketvpn-gradle-%VERSION%.zip' -DestinationPath '%TEMP%\pocketvpn-gradle-%VERSION%-extract' -Force"
  if errorlevel 1 exit /b 1
  move "%TEMP%\pocketvpn-gradle-%VERSION%-extract\gradle-%VERSION%" "%GRADLE_HOME%" >nul
  del "%TEMP%\pocketvpn-gradle-%VERSION%.zip" >nul 2>nul
  rmdir "%TEMP%\pocketvpn-gradle-%VERSION%-extract" >nul 2>nul
)
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
