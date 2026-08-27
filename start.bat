@echo off
echo Starting TechConnect...

set JAVA=%JAVA_HOME%\bin\java.exe
if not exist "%JAVA%" set JAVA=C:\Users\balch\.vscode\extensions\redhat.java-1.55.0-win32-x64\jre\21.0.11-win32-x86_64\bin\java.exe
set JAR=%~dp0java-server\target\techconnect-server-1.0.0.jar

if not exist "%JAR%" (
    echo ERROR: JAR not found: %JAR%
    pause
    exit /b 1
)

echo Starting server...
start "TechConnect Server" "%JAVA%" -jar "%JAR%"

echo Waiting for server to start...
timeout /t 8 /nobreak >nul

echo.
echo Server running at: http://localhost:8080
echo.
echo Starting public tunnel - your URL will appear below.
echo Keep this window open while sharing!
echo.

ssh -R 80:localhost:8080 serveo.net
pause
