# TheUnderScanner - Deployment Guide

## Quick Start

To deploy the app to your connected Android device, simply run:

```powershell
.\deploy.ps1
```

That's it! The script will automatically:
1. ✅ Set up Java 17 environment
2. ✅ Check for connected devices
3. ✅ Build the APK
4. ✅ Uninstall old version
5. ✅ Install new version

## Prerequisites

- Android device connected via USB with USB debugging enabled
- ADB drivers installed
- Java 17 installed at: `C:\Program Files\Eclipse Adoptium\jdk-17.0.3.7-hotspot`

## Troubleshooting

### "No Android device connected"
- Connect your device via USB
- Enable USB debugging in Developer Options
- Accept the USB debugging prompt on your device

### "Build failed"
- Make sure you're in the project root directory
- Check that Java 17 is installed correctly
- Try running `.\gradlew.bat clean` first

### "Installation failed"
- Try manually uninstalling the app from your device first
- Check that your device has enough storage space
- Verify USB debugging is still enabled

## Alternative Methods

### Build only (no install)
```powershell
.\build-with-java17.ps1
```

### Manual deployment
```bash
# Build
.\build-with-java17.ps1

# Uninstall
adb uninstall com.underscanner.theunderscannerapp

# Install
adb install .\app\build\outputs\apk\debug\app-debug.apk
```

## Files Created

- `deploy.ps1` - Main deployment script (build + uninstall + install)
- `build-with-java17.ps1` - Build-only script with Java 17
- `gradle.properties` - Contains `org.gradle.java.home` setting for Java 17
