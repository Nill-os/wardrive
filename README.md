# wardrive-esp32

A standalone car wardriving rig built from three cheap ESP32 boards. It logs 2.4 GHz WiFi networks and Bluetooth LE devices with GPS positions, writes [WigleWifi-1.6](https://api.wigle.net/) CSVs to a microSD card, and uploads to **WiGLE** and **wdgwars.pl** by itself when you get home. No phone is needed. Add a phone for a live dashboard, extra scanning and car-screen controls.

![Rig topology](docs/topology.svg)

| Board | Hardware | Job |
|---|---|---|
| **wifi_node** | ESP32-S3 + NEO-6M GPS | WiFi sniffer, GPS and geotagging |
| **ble_node** | ESP32-S3 | BLE scanner |
| **cyd_node** | CYD ESP32-2432S028 (dual-USB, ST7789) | Touchscreen UI, SD logging, uploads, Bluetooth link to the phone |

## Features

- **Touchscreen control:** start/stop, upload and re-link, a live status bar, and 5 tabs.
- **Resumes after power cuts:** it picks up where it left off when the car restarts.
- **Automatic upload on arriving home** (geofence), or manual upload.
- **Smart de-duplication:** distance-based for WiFi, per-run for BLE. It rides out GPS gaps of up to 15 s.
- **Idle auto-stop** after 30 minutes parked, to protect the car battery.
- **Status LEDs** on every board, so you can read the rig without a screen.
- **Optional phone app** ([Wardrive Bridge](https://github.com/Nill-os/wardrive-bridge)) over Bluetooth LE, with USB as a fallback. It also drives a map overlay and Android Auto.

## Get started

1. **[Hardware](docs/HARDWARE.md):** parts list, wiring diagram, pin tables and assembly.
2. **[Build and flash](docs/BUILD_AND_FLASH.md):** PlatformIO setup and flashing each board.
3. **[Config](docs/CONFIG.md):** `config.cfg` on the SD card (WiFi, upload keys, home geofence).
4. **[Using the rig](docs/USING_THE_RIG.md):** screen, buttons, LEDs, dock-mode uploads and power behaviour.
5. **[Phone app](docs/PHONE_APP.md):** Wardrive Bridge, the Organic Maps overlay, Android Auto and the BLE protocol.
6. **[Troubleshooting](docs/TROUBLESHOOTING.md)**
7. **[Design notes](docs/DESIGN_NOTES.md):** internals, link protocols and known limitations.

```
pio run -e wifi_node -t upload
pio run -e ble_node  -t upload
pio run -e cyd_node  -t upload
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

This rig only **listens** to broadcasts that devices send to everyone. It never connects to or attacks networks. Laws on collecting and publishing wireless data differ from country to country; check yours. The Wardrive Bridge app drops `_nomap` SSIDs, but the rig firmware doesn't filter them yet, so honour that opt-out yourself before sharing raw CSVs, and think twice before uploading data collected around private homes, including your own. See [Config → Keep it private](docs/CONFIG.md#keep-it-private).

## Repository layout

```
src/wifi_node/   src/ble_node/   src/cyd_node/   one firmware per board
lib/WardriveShared/                             CSV writer, uploader, config, BLE link
docs/                                           guides and diagrams
tools/                                          BLE link tester, SD-card CSV organizer
config.cfg.example                              template for the SD card
```

## License

[MIT](LICENSE)
