# Deploy script for TheUnderScanner app
# This script builds, uninstalls old version, and installs new version on connected device

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "TheUnderScanner - Deploy Script" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

# Set Java 17 environment
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.3.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# ADB path
$ADB = "C:\Users\dmitr\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# App package name
$PACKAGE = "com.underscanner.theunderscannerapp"

# Check if device is connected
Write-Host "Checking for connected devices..." -ForegroundColor Yellow
& $ADB devices
$devices = & $ADB devices | Select-String "device$"
if ($devices.Count -eq 0) {
    Write-Host "ERROR: No Android device connected!" -ForegroundColor Red
    Write-Host "Please connect your device and enable USB debugging." -ForegroundColor Red
    exit 1
}
Write-Host "Device found!" -ForegroundColor Green
Write-Host ""

# Build the APK
Write-Host "Building APK with Java 17..." -ForegroundColor Yellow
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Build failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Build successful!" -ForegroundColor Green
Write-Host ""

# Uninstall old version
Write-Host "Uninstalling old version..." -ForegroundColor Yellow
& $ADB uninstall $PACKAGE 2>$null
Write-Host "Old version uninstalled (if it existed)." -ForegroundColor Green
Write-Host ""

# Install new version
Write-Host "Installing new version..." -ForegroundColor Yellow
$APK_PATH = ".\app\build\outputs\apk\debug\app-debug.apk"
& $ADB install $APK_PATH
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: Installation failed!" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "Deployment successful!" -ForegroundColor Green
Write-Host "The app is now installed on your device." -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Cyan
