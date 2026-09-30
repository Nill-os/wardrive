<p align="center"><img src="docs/banner.svg" alt="Wardrive" width="100%"></p>

# wardrive

A standalone car wardriving rig built from three cheap ESP32 boards. It logs 2.4 GHz WiFi networks and Bluetooth LE devices with GPS positions, writes [WigleWifi-1.6](https://api.wigle.net/) CSVs to a microSD card, and uploads to **WiGLE** and **wdgwars.pl** by itself when you get home. No phone is needed. Add a phone for a live dashboard, extra scanning and car-screen controls.

<p align="center"><img src="docs/topology.svg" alt="Rig topology" width="100%"></p>

| Board | Hardware | Job |
|---|---|---|
| **wifi_node** | ESP32-S3 + NEO-6M GPS | WiFi sniffer, GPS and geotagging |
| **ble_node** | ESP32-S3 | BLE scanner |
| **cyd_node** | CYD ESP32-2432S028 (dual-USB, ST7789) | Touchscreen UI, SD logging, uploads, Bluetooth link to the phone |

## Features

- **Touchscreen control:** start/stop, upload and re-link, a live status bar, and 4 tabs.
- **Resumes after power cuts:** it picks up where it left off when the car restarts.
- **Automatic upload on arriving home** (geofence), or manual upload.
- **Smart de-duplication:** distance-based for WiFi, per-run for BLE. It rides out GPS gaps of up to 15 s.
- **Idle auto-stop** after 30 minutes parked, to protect the car battery.
- **Status LEDs** on every board, so you can read the rig without a screen.
- **Service mode:** park the rig on WiFi to download its session logs from any browser and flash new firmware **over the air** - no unplugging. Started from the phone app or a touch on the screen; scanning pauses while it's up.
- **Optional phone app** ([Nill OS - Wardriver](android/)): paired Bluetooth LE link with USB as a fallback, phone scanning (incl. 5 GHz), detections, live rig telemetry (RAM, SD space, link health), a "new finds this run" counter, exports (WigleWifi CSV, GPX, KML, Aircrack/airodump CSV), a widget and a quick-settings tile.
- **Map and car display** ([Organic Maps overlay](organic-maps-overlay/)): live status on the map and in Android Auto, with a start/stop button in the car.

## Screenshots

<p align="center">
  <img src="docs/screenshots/phone-dashboard.png" width="205" alt="Dashboard">
  <img src="docs/screenshots/phone-wifi-tools.png" width="205" alt="WiFi tools">
  <img src="docs/screenshots/phone-bluetooth-tools.png" width="205" alt="Bluetooth tools">
  <img src="docs/screenshots/phone-settings.png" width="205" alt="Settings">
</p>
<p align="center"><em>The Nill OS - Wardriver phone app: live dashboard, WiFi &amp; Bluetooth tools, and rig customization. (Account totals and map are hidden here for privacy.)</em></p>

<p align="center">
  <img src="docs/screenshots/desktop-uploader.png" width="330" alt="Desktop tool - logs & report">
  <img src="docs/screenshots/desktop-flash.png" width="330" alt="Desktop tool - flash">
</p>
<p align="center">
  <img src="docs/screenshots/desktop-field-report.png" width="480" alt="Field report">
</p>
<p align="center"><em>The desktop app is the one-stop tool: pull logs off the SD card, over USB, or over WiFi (no card removal) and build an interactive field report (map, search, filters, Flock/Flipper/skimmer detection); <b>flash</b> any board over USB or the CYD over the air; and <b>manage</b> the rig from a serial console. (Map above is demo data, not a real drive.)</em></p>

## Get started

1. **[Hardware](docs/HARDWARE.md):** parts list, wiring diagram, pin tables and assembly.
2. **[Build and flash](docs/BUILD_AND_FLASH.md):** PlatformIO setup and flashing each board.
3. **[Config](docs/CONFIG.md):** `config.cfg` on the SD card (WiFi, upload keys, home geofence).
4. **[Using the rig](docs/USING_THE_RIG.md):** screen, buttons, LEDs, dock-mode uploads and power behaviour.
5. **[Phone app](docs/PHONE_APP.md):** Nill OS - Wardriver, the Organic Maps overlay, Android Auto and the BLE protocol.
6. **[Troubleshooting](docs/TROUBLESHOOTING.md)**
7. **[Design notes](docs/DESIGN_NOTES.md):** internals, link protocols and known limitations.

```
git clone https://github.com/Nill-os/wardrive.git && cd wardrive
cd firmware && pio run -e wifi_node -t upload && pio run -e ble_node -t upload && pio run -e cyd_node -t upload
cd ../android && ./gradlew :app:assembleDebug        # the phone app
```

## Wiring at a glance

![Wiring](docs/wiring.svg)

| From | To |
|---|---|
| GPS TX / RX / VCC / GND | wifi_node GPIO18 / GPIO17 / 3V3 / GND |
| ble_node GPIO8 (TX) | wifi_node GPIO8 (RX) |
| wifi_node GPIO3 (TX) | ble_node GPIO3 (RX) |
| wifi_node GPIO13 (TX) | CYD CN1 IO27 (RX) |
| CYD CN1 IO22 (TX) | wifi_node GPIO14 (RX) |
| GND | GND on every board (common ground) |

## Be responsible

This rig only **listens** to broadcasts that devices send to everyone. It never connects to or attacks networks. Laws on collecting and publishing wireless data differ from country to country; check yours. Both the app and the rig drop `_nomap` SSIDs (WiGLE's opt-out convention), and both can exclude an area around home (the rig via `exclude_radius_m` in `config.cfg`). Still, think twice before uploading data collected around private homes, including your own. See [Config → Keep it private](docs/CONFIG.md#keep-it-private).

## Repository layout

```
docs/                    guides for the whole system (start here) + wiring and topology diagrams
firmware/                the three ESP32 boards - PlatformIO project
  src/wifi_node/  src/ble_node/  src/cyd_node/      one firmware per board
  lib/WardriveShared/    CSV writer, uploader, config parser, BLE link
  tools/                 BLE link tester, desktop upload/organizer
  config.cfg.example     template for the SD card
android/                 Nill OS - Wardriver - the Android app (Gradle project)
organic-maps-overlay/    patch that adds the status overlay to Organic Maps + apply script
```

## License

[MIT](LICENSE), except `organic-maps-overlay/`, which is Apache-2.0 like Organic Maps.
