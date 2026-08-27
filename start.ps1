$env:JAVA_HOME = "C:\Users\balch\.vscode\extensions\redhat.java-1.55.0-win32-x64\jre\21.0.11-win32-x86_64"
$jar = "C:\Users\balch\OneDrive\Documents\TechConnect\java-server\target\techconnect-server-1.0.0.jar"

Write-Host "Starting TechConnect server..." -ForegroundColor Cyan
Start-Process -FilePath "$env:JAVA_HOME\bin\javaw.exe" -ArgumentList "-jar", $jar -WindowStyle Hidden

Write-Host "Waiting for server to start..." -ForegroundColor Yellow
Start-Sleep -Seconds 8

Write-Host ""
Write-Host "Server running at: http://localhost:8080" -ForegroundColor Green
Write-Host ""
Write-Host "Starting public tunnel (keep this window open)..." -ForegroundColor Cyan
Write-Host "Your public URL will appear below:" -ForegroundColor Yellow
Write-Host ""

ssh -R 80:localhost:8080 serveo.net
