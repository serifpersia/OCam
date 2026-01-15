@echo off
setlocal

:main_menu
cls
echo --------------------
echo  OCam Control Panel 
echo --------------------
echo 1. Check ADB
echo 2. Build and Install OBS Plugin
echo 3. Install Android App
echo 4. Start ADB Reverse
echo 5. Fetch Latest Release
echo q. Quit
echo.
set /p choice="Enter your choice: "

if /i "%choice%"=="1" goto check_adb
if /i "%choice%"=="2" goto build_obs_plugin
if /i "%choice%"=="3" goto install_android_app
if /i "%choice%"=="4" goto start_adb_reverse
if /i "%choice%"=="5" goto fetch_latest_release
if /i "%choice%"=="q" goto :eof
echo Invalid choice.
pause
goto main_menu

:check_adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo ADB is not installed or not in your PATH.
    echo Please install Android Debug Bridge and add it to your PATH.
) else (
    echo ADB is installed.
)
pause
goto main_menu

:build_obs_plugin
echo Building and installing OBS plugin...
if exist "obs-plugin\build" (
    rmdir /s /q "obs-plugin\build"
)
mkdir "obs-plugin\build"
cd "obs-plugin\build"
cmake ..
cmake --build . --config Release
cmake --install . --config Release
cd ..\..
echo OBS plugin built and installed successfully.
pause
goto main_menu

:install_android_app
set "APK_PATH=android\app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" (
    echo Found APK at %APK_PATH%. Installing...
    adb install -r "%APK_PATH%"
) else (
    echo Default APK not found at %APK_PATH%.
    echo Please build the Android app first, or enter the path to the APK file manually:
    set /p "apk_path_manual=Enter path: "
    if exist "%apk_path_manual%" (
        adb install -r "%apk_path_manual%"
    ) else (
        echo File not found: %apk_path_manual%
    )
)
pause
goto main_menu

:start_adb_reverse
echo Starting adb reverse...
adb reverse tcp:27183 tcp:27183
adb reverse tcp:27184 tcp:27184
echo ADB reverse started. Press any key to stop and return to the menu.
pause >nul
adb reverse --remove-all
echo ADB reverse stopped.
pause
goto main_menu

:fetch_latest_release
echo Fetching latest release...
powershell -Command "try { $apiUrl = 'https://api.github.com/repos/serifpersia/OCam/releases/latest'; $response = Invoke-RestMethod -Uri $apiUrl; $downloadUrl = ($response.assets | Where-Object { $_.name -eq 'OCam.zip' }).browser_download_url; if ($downloadUrl) { Write-Host 'Downloading from ' $downloadUrl; Invoke-WebRequest -Uri $downloadUrl -OutFile 'OCam.zip'; if (Test-Path 'release') { Remove-Item -Recurse -Force 'release' }; New-Item -ItemType Directory -Force -Path 'release' | Out-Null; Expand-Archive -Path 'OCam.zip' -DestinationPath 'release'; Remove-Item 'OCam.zip'; Write-Host 'Latest release downloaded and extracted to ''release'' directory.'; } else { Write-Host 'Could not find the latest release zip file.'; } } catch { Write-Host 'Error fetching release: ' $_.Exception.Message; }"
pause
goto main_menu