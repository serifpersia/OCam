import sys
import qdarktheme
from PyQt6.QtWidgets import (QApplication, QMainWindow, QLabel, QVBoxLayout, QWidget, 
                             QPushButton, QHBoxLayout, QScrollArea, QFrame, QMessageBox,
                             QInputDialog, QLineEdit, QDialog, QFormLayout, QCheckBox)
from PyQt6.QtCore import Qt, QTimer, QSize
from PyQt6.QtGui import QIcon
import adbutils
import re
import time

class DeviceCard(QFrame):
    def __init__(self, device: adbutils.AdbDevice, parent=None):
        super().__init__(parent)
        self.device = device
        self.setFrameShape(QFrame.Shape.StyledPanel)
        self.setFrameShadow(QFrame.Shadow.Raised)
        
        layout = QHBoxLayout(self)
        
        # Device Info
        info_layout = QVBoxLayout()
        try:
            model = device.prop.model
            name = device.prop.name
            serial = device.serial
            # Check state
            state = device.get_state()
        except:
            model = "Unknown"
            name = "Unknown"
            serial = device.serial
            state = "Offline/Unauthorized"

        self.lbl_name = QLabel(f"<b>{model}</b> ({name})")
        self.lbl_serial = QLabel(f"Serial: {serial}")
        self.lbl_state = QLabel(f"State: {state}")
        
        info_layout.addWidget(self.lbl_name)
        info_layout.addWidget(self.lbl_serial)
        info_layout.addWidget(self.lbl_state)
        layout.addLayout(info_layout)
        
        # Actions
        btn_layout = QVBoxLayout()
        
        self.btn_connect = QPushButton("Switch to Wireless (TCP/IP)")
        self.btn_connect.setToolTip("Get device IP, enable TCP/IP 5555, and connect wirelessly.")
        self.btn_connect.clicked.connect(self.connect_tcpip)
        
        self.btn_disconnect = QPushButton("Disconnect")
        self.btn_disconnect.clicked.connect(self.disconnect)
        self.btn_disconnect.setEnabled(":" in serial) # Only for wireless
        
        # If already wireless, disable the switch button
        self.btn_connect.setEnabled(":" not in serial)

        btn_layout.addWidget(self.btn_connect)
        btn_layout.addWidget(self.btn_disconnect)
        layout.addLayout(btn_layout)
        
    def get_wlan_ip(self):
        """Attempts to get IP from wlan0 interface using shell command."""
        try:
            # adbutils.AdbDevice.get_ip_address() does not exist in some versions/implementations
            # Relying on shell command parsing as proven working
            
            # Parse ip addr show wlan0
            output = self.device.shell("ip addr show wlan0")
            
            # Look for inet <IP>/<CIDR>
            match = re.search(r"inet\s+([\d\.]+)", output)
            if match:
                return match.group(1)
        except:
            pass
        return None

    def connect_tcpip(self):
        # Pause refresh timer to prevent this widget from being deleted while we work
        main_win = self.window()
        if hasattr(main_win, 'timer'):
            main_win.timer.stop()

        try:
            # Simple flow: switch to tcpip 5555 then connect
            ip = self.get_wlan_ip()
            
            if not ip:
                # Attempt to turn on WiFi
                print("IP not found, attempting to enable WiFi...")
                self.device.shell("svc wifi enable")
                
                # Retry getting IP for a few seconds
                for _ in range(10): # Increased retry count
                    QApplication.processEvents() # Keep UI responsive
                    time.sleep(1)
                    ip = self.get_wlan_ip()
                    if ip:
                        break
            
            if not ip:
                try:
                    QMessageBox.warning(self, "Error", "Could not get IP address. tried 'svc wifi enable' and parsing wlan0 but still no IP.\nPlease manually enable WiFi on the device.")
                except RuntimeError:
                    pass # Widget might be deleted
                return
            
            # Step 1: Enable TCP/IP on port 5555
            self.device.tcpip(5555)
            
            # Step 2: Auto Connect
            # Give it a moment for the device to restart its adbd in TCP mode
            time.sleep(1) # 1-second delay
            output = adbutils.adb.connect(f"{ip}:5555")
            
            try:
                QMessageBox.information(self, "Success", f"Enabled Wireless!\nDevice IP: {ip}\nConnect Output: {output}")
            except RuntimeError:
                pass
                
        except Exception as e:
            try:
                QMessageBox.critical(self, "Error", str(e))
            except RuntimeError:
                pass
        finally:
            # Restart timer and Force Refresh
            if hasattr(main_win, 'timer'):
                main_win.timer.start()
            if hasattr(main_win, 'refresh_devices'):
                main_win.refresh_devices()

    def disconnect(self):
        try:
            adbutils.adb.disconnect(self.device.serial)
            QMessageBox.information(self, "Success", f"Disconnected {self.device.serial}")
        except Exception as e:
            QMessageBox.critical(self, "Error", str(e))
        finally:
             # Force Refresh
            main_win = self.window()
            if hasattr(main_win, 'refresh_devices'):
                main_win.refresh_devices()

class AddDeviceDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Add Wireless Device")
        self.setFixedWidth(400)
        
        layout = QVBoxLayout(self)
        
        layout.addWidget(QLabel("<h3>Connect via IP</h3>"))
        layout.addWidget(QLabel("Enter the IP and Port of a device that already has wireless debugging enabled."))
        
        form_layout = QFormLayout()
        self.ip_input = QLineEdit()
        self.ip_input.setPlaceholderText("192.168.1.x:5555")
        form_layout.addRow("Device Address:", self.ip_input)
        layout.addLayout(form_layout)
        
        btn_connect = QPushButton("Connect")
        btn_connect.clicked.connect(self.do_connect)
        layout.addWidget(btn_connect)

    def do_connect(self):
        addr = self.ip_input.text().strip()
        if not addr: return
        try:
            output = adbutils.adb.connect(addr)
            QMessageBox.information(self, "Result", f"Connect result: {output}")
            self.accept()
        except Exception as e:
            QMessageBox.critical(self, "Error", str(e))

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()

        self.setWindowTitle("OCam ADB Manager")
        self.setGeometry(100, 100, 500, 600)

        # Main Layout
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        self.main_layout = QVBoxLayout(central_widget)

        # Header
        header_layout = QHBoxLayout()
        
        # Title for the device list
        header_layout.addWidget(QLabel("<h2>Devices</h2>"))
        header_layout.addStretch() # Pushes everything after it to the right

        # Global reverse tunnel checkbox
        self.chk_global_reverse = QCheckBox("Reverse Tunnel (OBS)")
        self.chk_global_reverse.setToolTip("Enables adb reverse for ports 27183/27184 on all connected devices (Required for OCam Source)")
        self.chk_global_reverse.toggled.connect(self.toggle_global_reverse_ports)
        header_layout.addWidget(self.chk_global_reverse)
        
        # Refresh and Add Device buttons
        self.btn_refresh = QPushButton("Refresh")
        self.btn_refresh.clicked.connect(self.refresh_devices)
        header_layout.addWidget(self.btn_refresh)
        
        self.btn_pair = QPushButton("Add Wireless Device")
        self.btn_pair.clicked.connect(self.show_add_dialog)
        header_layout.addWidget(self.btn_pair)

        self.main_layout.addLayout(header_layout)

        # Device List Area
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll_content = QWidget()
        self.scroll_layout = QVBoxLayout(self.scroll_content)
        self.scroll_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
        self.scroll.setWidget(self.scroll_content)
        self.main_layout.addWidget(self.scroll)

        # Refresh Timer
        self.timer = QTimer()
        self.timer.timeout.connect(self.refresh_devices)
        self.timer.start(5000) # Auto refresh every 5s

        self.refresh_devices()

    def refresh_devices(self):
        # Clear existing
        for i in range(self.scroll_layout.count()):
            w = self.scroll_layout.itemAt(i).widget()
            if w:
                w.deleteLater()
        
        try:
            devices = adbutils.adb.device_list()
            self.update_global_reverse_checkbox() # Update global checkbox status

            if not devices:
                self.scroll_layout.addWidget(QLabel("No devices connected"))
            
            for d in devices:
                try:
                    card = DeviceCard(d)
                    self.scroll_layout.addWidget(card)
                except Exception as card_err:
                    self.scroll_layout.addWidget(QLabel(f"Error creating card: {card_err}"))
                
        except Exception as e:
            self.scroll_layout.addWidget(QLabel(f"Error checking ADB: {e}"))

    def show_add_dialog(self):
        dialog = AddDeviceDialog(self)
        if dialog.exec():
            self.refresh_devices()

    def toggle_global_reverse_ports(self, checked):
        ports = [27183, 27184, 27185]
        
        # Pause refresh timer to prevent race conditions during adb operations
        if hasattr(self, 'timer'):
            self.timer.stop()

        try:
            devices = adbutils.adb.device_list()
            if not devices:
                QMessageBox.warning(self, "No Devices", "No devices connected to apply reverse tunnel.")
                # Revert checkbox state if no devices
                self.chk_global_reverse.blockSignals(True)
                self.chk_global_reverse.setChecked(not checked)
                self.chk_global_reverse.blockSignals(False)
                return

            for d in devices:
                try:
                    if checked:
                        for port in ports:
                            d.reverse(f"tcp:{port}", f"tcp:{port}")
                    else:
                        for port in ports:
                            d.reverse_remove(f"tcp:{port}")
                except Exception as e:
                    # Log or show warning for individual device failures, but continue for others
                    print(f"Warning: Failed to set reverse tunnel for device {d.serial}: {e}")
                    QMessageBox.warning(self, "Reverse Tunnel Warning", f"Failed for device {d.serial}: {e}")
            
            # Optionally show a success message after trying all devices
            # QMessageBox.information(self, "Success", "Reverse tunnel operation attempted on all devices.")

        except Exception as e:
            QMessageBox.critical(self, "Global Reverse Tunnel Error", str(e))
            # Revert checkbox state if a critical error occurred
            self.chk_global_reverse.blockSignals(True)
            self.chk_global_reverse.setChecked(not checked)
            self.chk_global_reverse.blockSignals(False)
        finally:
            # Always restart timer and force refresh
            if hasattr(self, 'timer'):
                self.timer.start()
            self.refresh_devices()

    def update_global_reverse_checkbox(self):
        # Determine if the global reverse tunnel checkbox should be checked.
        # This uses adb reverse --list from the first available device,
        # which based on testing, provides a 'global' view of tunnels.
        is_reversed = False
        ports_to_check = ["27183", "27184"]

        try:
            devices = adbutils.adb.device_list()
            if devices:
                # Use the first device to query global reverse status
                first_device = devices[0]
                active_reverses = list(first_device.reverse_list())
                
                found_count = 0
                for item in active_reverses:
                    if "27183" in item.remote and "27183" in item.local:
                        found_count += 1
                    if "27184" in item.remote and "27184" in item.local:
                        found_count += 1
                
                if found_count >= len(ports_to_check):
                    is_reversed = True
        except Exception as e:
            print(f"Warning: Could not determine global reverse status: {e}")
            # If an error occurs, leave is_reversed as False and don't update

        # Update checkbox state without triggering the toggled signal
        if hasattr(self, 'chk_global_reverse') and self.chk_global_reverse.isChecked() != is_reversed:
            self.chk_global_reverse.blockSignals(True)
            self.chk_global_reverse.setChecked(is_reversed)
            self.chk_global_reverse.blockSignals(False)

def main():
    app = QApplication(sys.argv)
    # Apply dark theme (compatibility fix)
    try:
        if hasattr(qdarktheme, 'setup_theme'):
            qdarktheme.setup_theme()
        else:
            app.setStyleSheet(qdarktheme.load_stylesheet())
    except Exception as e:
        print(f"Failed to load dark theme: {e}")

    window = MainWindow()
    window.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
