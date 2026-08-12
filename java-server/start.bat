@echo off
setlocal EnableDelayedExpansion

set "ROOT=%~dp0"
set "JDK_DIR=%ROOT%.jdk"
set "MVN_DIR=%ROOT%.mvn"
set "JDK_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%%2B11/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.zip"
set "MVN_URL=https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip"

echo.
echo  ████████╗███████╗ ██████╗██╗  ██╗ ██████╗ ██████╗ ███╗  ██╗███╗  ██╗███████╗ ██████╗████████╗
echo     ██╔══╝██╔════╝██╔════╝██║  ██║██╔════╝██╔═══██╗████╗ ██║████╗ ██║██╔════╝██╔════╝╚══██╔══╝
echo     ██║   █████╗  ██║     ███████║██║      ██║   ██║██╔██╗██║██╔██╗██║█████╗  ██║        ██║
echo     ██║   ██╔══╝  ██║     ██╔══██║██║      ██║   ██║██║╚████║██║╚████║██╔══╝  ██║        ██║
echo     ██║   ███████╗╚██████╗██║  ██║╚██████╗╚██████╔╝██║ ╚███║██║ ╚███║███████╗╚██████╗   ██║
echo     ╚═╝   ╚══════╝ ╚═════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚══╝╚═╝  ╚══╝╚══════╝ ╚═════╝   ╚═╝
echo.
echo  Java / Spring Boot Server  ^|  Starting up...
echo  ─────────────────────────────────────────────────────────────────────────────
echo.

:: ── 1. Locate or download JDK 21 ─────────────────────────────────────────────
if exist "%JDK_DIR%\bin\javac.exe" (
    echo  [JDK]   Found cached JDK at .jdk\
    set "JAVA_HOME=%JDK_DIR%"
    set "PATH=%JDK_DIR%\bin;%PATH%"
    goto :check_mvn
)

where javac >nul 2>&1
if !errorlevel! equ 0 (
    echo  [JDK]   javac found in PATH
    goto :check_mvn
)

echo  [JDK]   Downloading OpenJDK 21 ^(~190 MB — one-time only^)...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "& { $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%JDK_URL%' -OutFile '%ROOT%_jdk.zip' }"
if !errorlevel! neq 0 (
    echo  ERROR: JDK download failed. Check your internet connection.
    pause & exit /b 1
)

echo  [JDK]   Extracting...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Expand-Archive -Path '%ROOT%_jdk.zip' -DestinationPath '%ROOT%_jdk_tmp' -Force"
for /d %%d in ("%ROOT%_jdk_tmp\jdk-*") do move "%%d" "%JDK_DIR%" >nul
del "%ROOT%_jdk.zip" >nul 2>&1
rd /s /q "%ROOT%_jdk_tmp" >nul 2>&1

set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JDK_DIR%\bin;%PATH%"
echo  [JDK]   Installed to .jdk\

:: ── 2. Locate or download Maven ───────────────────────────────────────────────
:check_mvn
if exist "%MVN_DIR%\bin\mvn.cmd" (
    echo  [MVN]   Found cached Maven at .mvn\
    set "MVN=%MVN_DIR%\bin\mvn.cmd"
    goto :run
)

where mvn >nul 2>&1
if !errorlevel! equ 0 (
    echo  [MVN]   mvn found in PATH
    set "MVN=mvn"
    goto :run
)

echo  [MVN]   Downloading Apache Maven 3.9.9 ^(~10 MB — one-time only^)...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "& { $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%MVN_URL%' -OutFile '%ROOT%_mvn.zip' }"
if !errorlevel! neq 0 (
    echo  ERROR: Maven download failed. Check your internet connection.
    pause & exit /b 1
)

echo  [MVN]   Extracting...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Expand-Archive -Path '%ROOT%_mvn.zip' -DestinationPath '%ROOT%_mvn_tmp' -Force"
for /d %%d in ("%ROOT%_mvn_tmp\apache-maven-3.9.6") do move "%%d" "%MVN_DIR%" >nul
del "%ROOT%_mvn.zip" >nul 2>&1
rd /s /q "%ROOT%_mvn_tmp" >nul 2>&1

set "MVN=%MVN_DIR%\bin\mvn.cmd"
echo  [MVN]   Installed to .mvn\

:: ── 3. Build and run ─────────────────────────────────────────────────────────
:run
echo.
echo  [INFO]  Building and starting TechConnect...
echo  [INFO]  Server will be available at http://localhost:8080
echo  [INFO]  Press Ctrl+C to stop.
echo.

cd /d "%ROOT%"
"%MVN%" spring-boot:run --no-transfer-progress

pause
