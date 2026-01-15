<div align=center>

 ## OCam   
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/serifpersia/OCam)](https://github.com/serifpersia/OCam/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-GPL2-yellow.svg)](https://opensource.org/licenses/gpl-2.0)



</div>



**OCam** alows your **Android** smartphone to be used as a high-quality, usb/wireless OBS camera source.

## Features
*   Utilizes your **Android** device's camera for high-quality video input.
*   Adjustable streaming parameters (resolution, FPS, bitrate).
*   Manual camera controls (exposure, focus, flash).
*   Cross-platform compatibility for **OBS Studio** plugin (**Windows**, **macOS**, **Linux**).
*   Easy setup via provided scripts.

## Installation

You have two main methods to install **OCam**: using a pre-built release package for convenience or compiling the project from source for the latest features and development.

### From Release

This is the recommended and easiest method for most users to quickly get **OCam** running.

#### Prerequisites
*   An **Android** device for the camera.
*   **OBS Studio** installed on your computer.
*   **ADB (Android Debug Bridge)** installed and configured on your computer (optional, for automatic APK installation).

#### Quick Install Steps

1.  **Run the Setup Script:**
    Navigate to the root of this repository in your terminal or command prompt and execute the appropriate script for your operating system:
    *   **Windows:** `run.bat`
    *   **macOS / Linux:** `./run.sh`

2.  **Fetch the Latest Release:**
    From the interactive menu, select the "**Fetch Latest Release**" option. This will:
    *   Download the latest `OCam.zip` package from the [GitHub Releases](https://github.com/serifpersia/OCam/releases) page.
    *   Extract its contents into a new `release` directory within your current working directory.
    The `release` directory will contain:
    *   `OCam.apk`: The **Android** application.
    *   `obs-ocam-source`: The **OBS Studio** plugin.

3.  **Install the Android App:**
    *   **Automatic (Recommended):** In the same `run.bat` or `run.sh` menu, select "**Install Android App**". This uses **ADB** to automatically install `OCam.apk` onto your connected **Android** device.
    *   **Manual:** Transfer the `OCam.apk` file from the `release` directory to your **Android** device and install it manually, or use `adb install release/OCam.apk` if **ADB** is set up.

4.  **Install the OBS Plugin:**
    Manually copy the entire `obs-ocam-source` plugin directory from the `release` folder into the designated **OBS Studio** plugins location for your operating system:
    *   **Windows:** Copy `obs-ocam-source` to `C:\ProgramData\obs-studio\plugins`.
    *   **macOS:** Copy `obs-ocam-source` to `~/Library/Application Support/obs-studio/plugins`.
    *   **Linux:** Copy `obs-ocam-source` to `~/.config/obs-studio/plugins`.

### Build from source:

#### Prerequisites

Ensure you have the following development tools installed on your system:

*   **Android SDK (with platform tools for ADB):** Necessary for building the **Android** application and communicating with your device.
*   **Git:** For cloning the repository.
*   **CMake:** Cross-platform build system.

**Operating System Specific Prerequisites:**

*   **Windows:**
    *   **Visual Studio 2022** (or later) with the "**Desktop development with C++**" workload installed.
*   **Linux:**
    *   **Build Essentials:** Compilers and build tools.
    *   **FFmpeg Development Libraries:** For video encoding/decoding.

    *   **Debian/Ubuntu** (e.g., Ubuntu, Mint):
        ```bash
        sudo apt update
        sudo apt install build-essential ffmpeg cmake
        ```
    *   **Fedora/CentOS/RHEL** (e.g., Fedora, AlmaLinux):
        ```bash
        sudo dnf install gcc-c++ ffmpeg-devel cmake
        ```
*   **Android App:**
    *   **Android Studio:** The official IDE for Android development, required to open the `android` project and build the `OCam.apk`.

#### Build & Install Steps

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/serifpersia/OCam.git
    cd OCam
    ```

2.  **Run the Setup Script:**
    Execute the appropriate script for your operating system:
    *   **Windows:** `run.bat`
    *   **macOS / Linux:** `./run.sh`

3.  **Build and Install OBS Plugin:**
    From the interactive menu, select the "**Build and Install OBS Plugin**" option. This will compile the **OBS Studio** plugin and automatically place it into the correct directory for your operating system.

4.  **Install the Android App:**
    You will still need to install the **Android** app. You can either:
    *   Build it from source within the `android` directory using **Android Studio**.
    *   Use the `OCam.apk` obtained from the latest release, as described in the [From Release](#from-release) section.

## Setting up ADB (Android Debug Bridge)

**ADB** is a powerful command-line tool that lets your computer communicate with your **Android** device. It's required for the automatic installation feature of the `run` scripts.

1.  **Enable Developer Options on Your Android Device:**
    *   Go to **Settings** > **About phone**.
    *   Tap on the **Build number** seven (7) times in a row. You will see a message saying, "You are now a developer!".

2.  **Enable USB Debugging:**
    *   Go back to the main **Settings** menu.
    *   Find the newly enabled **Developer options** menu (it might be under **Settings** > **System**).
    *   Inside **Developer options**, find and enable the **USB debugging** toggle.

3.  **Connect Your Device:**
    You can connect your computer to your device using either a USB cable or Wi-Fi.

    *   **Via USB (Most common):**
        1.  Plug your **Android** device into your computer using a USB cable.
        2.  On your phone's screen, you should see a prompt asking to "Allow USB debugging?".
        3.  Check the box for "Always allow from this computer" and tap **Allow**.
        4.  To verify the connection, open a terminal or command prompt on your computer and run `adb devices`. You should see your device's serial number listed.

    *   **Via Wi-Fi (Wireless ADB):**
        1.  First, connect your device via USB and ensure it is recognized by `adb devices`.
        2.  Run the following command in your terminal:
            ```bash
            adb tcpip 5555
            ```
        3.  You can now disconnect the USB cable.
        4.  Find your phone's local IP address (usually in **Settings** > **About phone** > **Status** or **Settings** > **Wi-Fi** > **(Your Network)**).
        5.  Connect to your device using its IP address:
            ```bash
            adb connect YOUR_PHONE_IP:5555
            ```
            (e.g., `adb connect 192.168.1.100:5555`)
        6.  Run `adb devices` again to confirm the wireless connection.

Once your device is connected via **ADB**, the "**Install Android App**" option in the scripts will work.

## Usage

Once both the **OCam** app is on your **Android** device and the **OBS Studio** plugin is installed, follow these steps to establish your wireless webcam connection:

1.  **Prepare Your Devices:**
    *   Ensure the **OCam Android** app is installed on your phone.
    *   Verify the `obs-ocam-source` plugin is correctly installed in **OBS Studio**.
    *   Connect your computer to your **Android** device via **ADB** (see [Setting up ADB](#setting-up-adb-android-debug-bridge)).
    *   Connect both your **Android** device and your computer to the **same Wi-Fi network**.

2.  **Establish ADB Reverse Tunnel:**
    *   This step is crucial for the **OBS Studio** plugin to communicate effectively with the **OCam** app on your phone, allowing for control commands (like changing resolution, focus, etc.) to be sent from OBS to your device.
    *   Run the setup script (`run.bat` for Windows or `./run.sh` for macOS/Linux) from your project root.
    *   From the interactive menu, select the "**Start ADB Reverse**" option.
    *   The script will establish the necessary TCP port forwarding tunnels and will then pause. **Keep this terminal window open and the script running throughout your OCam session.**
    *   When you are finished using OCam, return to this terminal and press **'q'** to stop the ADB reverse process and close the tunnels cleanly.

3.  **Add OCam Source in OBS Studio:**
    *   Open **OBS Studio** on your computer.
    *   In the "**Sources**" panel, click the `+` icon to add a new source.
    *   From the list, select "**OCam Source**".

4.  **Start OCam on Android:**
    Open the **OCam** app on your phone. Make sure obs plugin is added to your scene in OBS before starting the app on the android phone.

5.  **Initial Connection (Crucial Step):**
    *   A properties window for the **OCam Source** will appear. **It is highly recommended to initially accept the default settings without making any changes.**
    *   Click "**OK**" to add the source to your scene with its default configuration.
    *   The plugin will automatically attempt to discover and connect to your phone on the network. Once a successful connection is established, the live video feed from your phone's camera should appear in the **OBS** preview window.

6.  **Adjusting Settings (After Connection):**
    *   **After** the connection is stable and you can see the video streaming, you can then safely fine-tune your settings.
    *   Right-click on the "**OCam Source**" in the "**Sources**" panel and select "**Properties**".
    *   Here you can modify parameters such as:
        *   **Resolution**
        *   **Frames Per Second (FPS)**
        *   **Bitrate**
        *   **Toggle Flash**
        *   **Manual Camera Controls** (e.g., exposure/shutter speed, focus)

> **Important Note:** To ensure a smooth initial setup, always establish the connection using the plugin's default settings. Attempting to change complex video or camera settings before a stable connection is made can sometimes lead to connectivity issues.

## Contributing

Contributions are welcome!

## License

This project is licensed under the GPL2 License - see the [LICENSE](LICENSE) file for details.
