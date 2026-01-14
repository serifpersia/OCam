#!/bin/bash

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check for adb
check_adb() {
    if ! command_exists adb; then
        echo "ADB is not installed. Please install Android Debug Bridge."
        exit 1
    fi
    echo "ADB is installed."
}

# Function to build the OBS plugin
build_obs_plugin() {
    echo "Building OBS plugin..."
    # Remove previous build directory to ensure a clean build
    if [ -d "obs-plugin/build" ]; then
        rm -rf "obs-plugin/build"
    fi
    mkdir -p obs-plugin/build
    cd obs-plugin/build
    cmake ..
    make
    cd ../..
    echo "OBS plugin built successfully."
}

# Function to copy the OBS plugin
copy_obs_plugin() {
    echo "Copying OBS plugin..."
    PLUGIN_DIR="$HOME/.config/obs-studio/plugins/obs-ocam-source/bin/64bit"
    mkdir -p "$PLUGIN_DIR"
    cp obs-plugin/build/obs-ocam-source.so "$PLUGIN_DIR/"
    echo "OBS plugin copied to $PLUGIN_DIR"
}

# Function to install the Android app
install_android_app() {
    APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        echo "Found APK at $APK_PATH. Installing..."
        adb install -r "$APK_PATH"
    else
        echo "Default APK not found at $APK_PATH."
        echo "Please build the Android app first, or enter the path to the APK file manually:"
        read -r apk_path_manual
        if [ -f "$apk_path_manual" ]; then
            adb install -r "$apk_path_manual"
        else
            echo "File not found: $apk_path_manual"
        fi
    fi
}

# Function to start adb reverse
start_adb_reverse() {
    echo "Starting adb reverse..."
    adb reverse tcp:27183 tcp:27183
    adb reverse tcp:27184 tcp:27184
    echo "ADB reverse started. Press 'q' to stop and return to the menu."
    while true; do
        read -rsn1 key
        if [[ $key == "q" ]]; then
            break
        fi
    done
    adb reverse --remove-all
    echo "ADB reverse stopped."
}

# Main menu
main_menu() {
    while true; do
        echo "--------------------"
        echo " OCam Control Panel "
        echo "--------------------"
        echo "1. Check ADB"
        echo "2. Build OBS Plugin"
        echo "3. Copy OBS Plugin"
        echo "4. Install Android App"
        echo "5. Start ADB Reverse"
        echo "q. Quit"
        echo "Enter your choice:"
        read -r choice

        case $choice in
            1) check_adb ;;
            2) build_obs_plugin ;;
            3) copy_obs_plugin ;;
            4) install_android_app ;;
            5) start_adb_reverse ;;
            q) break ;;
            *) echo "Invalid choice. Please try again." ;;
        esac
    done
}

main_menu