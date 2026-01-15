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
    echo "Building and installing OBS plugin..."
    # Remove previous build directory to ensure a clean build
    if [ -d "obs-plugin/build" ]; then
        rm -rf "obs-plugin/build"
    fi
    mkdir -p obs-plugin/build
    cd obs-plugin/build
    cmake .. -DCMAKE_BUILD_TYPE=Release
    cmake --build . --config Release
    
    # Check for Linux and install to the correct user directory
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "Installing plugin for Linux..."
        PLUGIN_DIR="$HOME/.config/obs-studio/plugins/obs-ocam-source/bin/64bit"
        mkdir -p "$PLUGIN_DIR"
        # The output file is obs-ocam-source.so
        cp "obs-ocam-source.so" "$PLUGIN_DIR/"
        echo "Plugin installed to $PLUGIN_DIR"
    else
        # For other OSes, fallback to the default install command
        cmake --install . --config Release
    fi
    
    cd ../..
    echo "OBS plugin built and installed successfully."
}

# Function to install the Android app
install_android_app() {
    DEFAULT_APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
    
    if [ -f "$DEFAULT_APK_PATH" ]; then
        echo "Found APK at $DEFAULT_APK_PATH. Installing..."
        adb install -r "$DEFAULT_APK_PATH"
        return 0
    elif [ -d "release" ]; then
        # Check for APKs within the 'release' directory
        RELEASE_APKS=($(find release -name "*.apk" -print -quit)) # Find first APK and quit

        if [ ${#RELEASE_APKS[@]} -gt 0 ]; then
            RELEASE_DIR_APK="${RELEASE_APKS[0]}"
            echo "Found APK in release directory: $RELEASE_DIR_APK. Installing..."
            adb install -r "$RELEASE_DIR_APK"
            return 0
        fi
    fi

    echo "No APK found in default build path or release directory."
    echo "Please build the Android app first, or enter the path to the APK file manually:"
    read -r apk_path_manual
    if [ -f "$apk_path_manual" ]; then
        adb install -r "$apk_path_manual"
    else
        echo "File not found: $apk_path_manual"
    fi
}

# Function to start adb reverse
start_adb_reverse() {
    echo "Starting adb reverse..."
    adb reverse tcp:27183 tcp:27183
    adb reverse tcp:27184 tcp:27184
    adb reverse tcp:27185 tcp:27185
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

# Function to fetch the latest release
fetch_latest_release() {
    echo "Fetching latest release..."
    if ! command_exists curl; then
        echo "curl is not installed. Please install curl."
        exit 1
    fi
    if ! command_exists jq; then
        echo "jq is not installed. Please install jq."
        exit 1
    fi

    API_URL="https://api.github.com/repos/serifpersia/OCam/releases/latest"
    DOWNLOAD_URL=$(curl -s $API_URL | jq -r '.assets[] | select(.name == "OCam-v1.0.0.zip") | .browser_download_url')

    if [ -z "$DOWNLOAD_URL" ]; then
        echo "Could not find the latest release zip file."
        exit 1
    fi

    echo "Downloading from $DOWNLOAD_URL"
    curl -L -o OCam.zip "$DOWNLOAD_URL"

    if ! command_exists unzip; then
        echo "unzip is not installed. Please install unzip."
        exit 1
    fi
    
    if [ -d "release" ]; then
        rm -rf "release"
    fi
    mkdir -p release
    unzip OCam.zip -d release
    rm OCam.zip
    echo "Latest release downloaded and extracted to 'release' directory."
}

# Main menu
main_menu() {
    while true; do
        echo "--------------------"
        echo " OCam Control Panel "
        echo "--------------------"
        echo "1. Check ADB"
        echo "2. Build and Install OBS Plugin"
        echo "3. Install Android App"
        echo "4. Start ADB Reverse"
        echo "5. Fetch Latest Release"
        echo "q. Quit"
        echo "Enter your choice:"
        read -r choice

        case $choice in
            1) check_adb ;;
            2) build_obs_plugin ;;
            3) install_android_app ;;
            4) start_adb_reverse ;;
            5) fetch_latest_release ;;
            q) break ;;
            *) echo "Invalid choice. Please try again." ;;
        esac
    done
}

main_menu