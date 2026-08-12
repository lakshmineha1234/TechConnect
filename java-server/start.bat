@echo off
set "ROOT=%~dp0"
set "JAR=%ROOT%target\techconnect-server-1.0.0.jar"
set "JAVAW=%ROOT%.jdk\bin\javaw.exe"

echo TechConnect Server - Starting...

if not exist "%JAR%"   ( echo ERROR: JAR not found. & pause & exit /b 1 )
if not exist "%JAVAW%" ( echo ERROR: Java not found. & pause & exit /b 1 )

taskkill /F /IM javaw.exe >nul 2>&1

start "" "%JAVAW%" -jar "%JAR%"
timeout /t 10 /nobreak >nul
start "" "http://localhost:8080"
