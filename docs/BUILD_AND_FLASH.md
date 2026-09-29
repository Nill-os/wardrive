# Build and flash

## 1. Install PlatformIO

Use either:

- **VS Code** with the PlatformIO IDE extension, or
- **the command line**: `pip install platformio`, which gives you the `pio` command.

PlatformIO downloads the ESP32 toolchain and every library the first time you build. The libraries are TinyGPSPlus, NimBLE-Arduino and TFT_eSPI, and they are pinned in `platformio.ini`.

On Linux, add yourself to the `dialout` group (or your distro's equivalent) so you can open serial ports, then log out and back in:

```
sudo usermod -aG dialout $USER
```

## 2. Get the code

```
git clone https://github.com/Nill-os/wardrive-esp32.git
cd wardrive-esp32
```

## 3. Flash each board

Plug in **one board at a time**, so you know exactly which port is which board.

```
pio run -e wifi_node -t upload     # ESP32-S3 that gets the GPS
pio run -e ble_node  -t upload     # the other ESP32-S3
pio run -e cyd_node  -t upload     # the CYD screen board
```

Add `-t monitor` to watch the serial output after flashing. Press `Ctrl+C` to leave the monitor. If more than one board is plugged in, pick the port explicitly:

```
pio run -e cyd_node -t upload --upload-port /dev/ttyUSB0
pio device monitor -p /dev/ttyACM0 -b 115200
```

Typical ports:

| Board | Linux | Windows | macOS |
|---|---|---|---|
| ESP32-S3 (native USB) | `/dev/ttyACM*` | `COMx` | `/dev/cu.usbmodem*` |
| CYD (CH340 USB chip) | `/dev/ttyUSB*` | `COMx` (needs the CH340 driver) | `/dev/cu.wchusbserial*` |

**Label the two S3 boards straight after flashing.** They are identical, and a swapped pair is the most common cause of a dead link.

### If an upload won't start

- **ESP32-S3:** hold **BOOT**, tap **RESET**, release **BOOT**, then upload again.
- **CYD:** unplug anything connected to its bottom serial header (UART0). A board transmitting into RX0 can block flashing.
- **CYD won't even power up:** you're using a USB-C to USB-C cable. Use USB-A to USB-C, or the micro-USB port.

## 4. Check each board is alive

Each board prints a `[heartbeat]` line every 2 seconds while `WARDRIVE_DEBUG` is `true` (the default, set at the top of each `main.cpp`).

| Board | What to look for |
|---|---|
| wifi_node | `gpsFix=1` and `sats=` once the GPS has a fix. `gpsChars` should climb even without a fix, which proves the GPS wiring works. `cydrx=` counts bytes from the CYD. |
| ble_node | `scanning=` and `sent=` (number of devices sent to wifi_node) |
| cyd_node | `sdOk=1`. `wlrx=` counts bytes from wifi_node and should keep climbing once everything is wired. `wifi=` and `ble=` are this run's counts. |

The wifi_node heartbeat also prints your current `lat`/`lon`. Remember that before you paste a log anywhere public.

If `cydrx` or `wlrx` stays at 0, see [Troubleshooting](TROUBLESHOOTING.md#rigdown--the-wired-link-carries-nothing).

## Building variations

- **A different ESP32-S3 board:** change `board =` in `[env:wifi_node]` and `[env:ble_node]`. If your board has no RGB LED on GPIO48, change the LED pin in `src/wifi_node/main.cpp` and `src/ble_node/main.cpp`.
- **A different CYD:** the display pin map is in `[env:cyd_node]` `build_flags`. A single-USB CYD with an **ILI9341** screen needs `ILI9341_DRIVER` in place of `ST7789_DRIVER`, and probably different inversion and rotation settings too (see [Design notes](DESIGN_NOTES.md#display-quirks)).
- **Quieter serial output:** set `WARDRIVE_DEBUG` to `false` in each `main.cpp` once the rig works.

## The SD-card organizer (optional)

`tools/organize_wardrive.py` is a small desktop GUI. It merges every session CSV on the SD card into one de-duplicated spreadsheet in `~/Wardrive_Reports`.

```
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
tools/run.sh
```

It needs Tk (`sudo apt install python3-tk` on Debian/Ubuntu).
