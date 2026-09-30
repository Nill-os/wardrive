# Build and flash

## 1. Install PlatformIO

Use either:

- **VS Code** with the PlatformIO IDE extension, or
- **the command line**: `pip install platformio`, which gives you the `pio` command.

PlatformIO downloads the ESP32 toolchain and every library the first time you build. The libraries are TinyGPSPlus, NimBLE-Arduino and TFT_eSPI, and they are pinned in `firmware/platformio.ini`.

On Linux, add yourself to the `dialout` group (or your distro's equivalent) so you can open serial ports, then log out and back in:

```
sudo usermod -aG dialout $USER
```

## 2. Get the code

```
git clone https://github.com/Nill-os/wardrive.git
cd wardrive/firmware
```

## 3. Flash each board

Run these from the `firmware/` folder.

Plug in **one board at a time**, so you know exactly which port is which board.

> **Flash all three from the same version of this repo.** The boards talk to each other at a fixed speed and protocol, so a board left on older firmware can't understand the others.

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
| cyd_node | `sdOk=1`. `wlrx=` counts bytes from wifi_node and should keep climbing once everything is wired. `wifi=` and `ble=` are this run's counts. `sniff=` counts the frames the CYD's own sniffer has heard. |

The wifi_node heartbeat also prints your current `lat`/`lon`. Remember that before you paste a log anywhere public.

If `cydrx` or `wlrx` stays at 0, see [Troubleshooting](TROUBLESHOOTING.md#rigdown--the-wired-link-carries-nothing).

## Building variations

- **A different ESP32-S3 board:** change `board =` in `[env:wifi_node]` and `[env:ble_node]`. If your board has no RGB LED on GPIO48, change the LED pin in `firmware/src/wifi_node/main.cpp` and `firmware/src/ble_node/main.cpp`.
- **A different CYD:** the display pin map is in `[env:cyd_node]` `build_flags`. A single-USB CYD with an **ILI9341** screen needs `ILI9341_DRIVER` in place of `ST7789_DRIVER`, and probably different inversion and rotation settings too (see [Design notes](DESIGN_NOTES.md#display-quirks)).
- **More sniffer nodes:** flash each extra `wifi_node` board with its own `-D NODE_COUNT=<N> -D NODE_INDEX=<0..N-1>` and the firmware splits the 2.4 GHz channels across them automatically. The easiest way is the **desktop app's Flash tab**: set *Sniffer nodes* to `N`, pick this board's number, and flash - it injects those flags for you (no `platformio.ini` edit). Adding nodes also needs a transport decision (ESP-NOW is the recommended direction); see [Design notes → Scaling to more sniffer nodes](DESIGN_NOTES.md#scaling-to-more-sniffer-nodes).
- **Quieter serial output:** set `WARDRIVE_DEBUG` to `false` in each `main.cpp` once the rig works.

## The desktop upload/organizer (optional)

`firmware/tools/organize_wardrive.py` is a small desktop GUI that pulls the rig's logs, organizes them, and builds an **interactive field report**. Two ways to get the logs:

- **GET FROM SD CARD** - plug the card into the PC (auto-detected on Linux, macOS, Windows).
- **GET FROM RIG (USB)** - *without removing the card*: plug the CYD in over USB; it reads the logs straight off the card over the serial cable (`sd list` / `sd get`), no WiFi needed.
- **GET FROM RIG (WIFI)** - *without removing the card*: put the rig in [service mode](USING_THE_RIG.md#service-mode-download-logs-update-firmware), enter its address (`nillos-wardriver.local` or its IP) and, if set, its `service_password`, and it downloads the logs over WiFi.

Either way it writes, under `~/Wardrive_Reports/report_<date>_<time>/`:

```
report.html                interactive field report - map + search + filters,
                             flags Flock cameras, Flipper Zeros, skimmers, Pineapples
sessions/2026-09-29/…      raw session files, one folder per drive date
by-type/  by-band/  by-security/  notable/    the same devices, grouped
combined/all_networks.wigle.csv (WiGLE-ready)  all_devices.xlsx
summary.txt
```

Nothing is deleted from the card. (The rig still does its own automatic WiGLE / wdgwars uploads; this is for keeping, organizing and exploring your own copy.)

```
cd firmware
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
tools/run.sh
```

It needs Tk (`sudo apt install python3-tk` on Debian/Ubuntu).

To add it to your applications menu (and Desktop) with an icon:

```
tools/install-desktop.sh
```

Then launch **Nill OS - Wardriver** like any app - `run.sh` sets up its Python environment on first run.

## Over-the-air updates (cyd_node)

The CYD can be reflashed over WiFi, so you don't have to pull it out of the car and plug in a cable for every firmware change. The two node boards (wifi_node, ble_node) are still flashed over USB - they spend their time in scan modes that don't hold a normal WiFi connection.

1. Put the rig in **service mode**: tap **RIG SERVICE MODE** in the phone app's Settings, or send `rig service on` over the CYD's USB serial. The rig joins the WiFi in `config.cfg`, pauses scanning, and shows its address (e.g. `http://192.168.1.198`) on screen and in the app.
2. Flash over the air:

```
cd firmware
pio run -e cyd_node_ota -t upload
```

`cyd_node_ota` is the same firmware as `cyd_node`, uploaded over WiFi to the mDNS host `nillos-wardriver` instead of a serial port. If `.local` mDNS doesn't resolve on your network, pass the address the rig showed: `pio run -e cyd_node_ota -t upload --upload-port 192.168.1.198`.

If you set `service_password` in `config.cfg` (recommended - see [Config](CONFIG.md) and [SECURITY.md](../SECURITY.md)), pass it to the OTA upload too: `pio run -e cyd_node_ota -t upload --upload-flags "--auth=YOURPASSWORD"`. The same password protects the log web server.

3. The rig reboots into the new firmware. Tap its screen (or send `rig service off`) to leave service mode and go back to normal.

The very first flash after changing the partition layout (or a brand-new board) still has to be over USB (`pio run -e cyd_node -t upload`) - OTA needs the two-slot partition table already running.
