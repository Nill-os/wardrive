# Design notes

These notes explain how the rig works inside, for anyone changing the code.

## Roles

| Board | Radio | Owns | Code |
|---|---|---|---|
| wifi_node | WiFi (promiscuous sniffer, channels 1–11) | GPS, geotagging, WiFi de-duplication, idle auto-stop | `src/wifi_node/` |
| ble_node | BLE (passive scan) | Per-run BLE de-duplication | `src/ble_node/` |
| cyd_node | BLE server (phone only) | SD card, `config.cfg`, CSV writer, uploader, touchscreen UI, run state | `src/cyd_node/` |

Code used by more than one board lives in `lib/WardriveShared/`:

- `WigleWriter`: the WigleWifi-1.6 CSV writer.
- `Uploader`: wdgwars and WiGLE uploads.
- `WardriveConfig`: the `config.cfg` parser.
- `CydBleLink`: the CYD's BLE server.

## Data flow

```
ble_node --UART 8/3--> wifi_node --UART 13/14--> cyd_node --BLE (USB fallback)--> phone
   BLE sightings        + GPS geotag, WiFi         SD CSV, uploads, screen
```

- **ble_node → wifi_node:** one line per BLE device, `MAC,RSSI,NAME,MFG_HEX`.
- **wifi_node → ble_node:** `START`, `STOP`, `OK`, `FAIL`, `LOWSTORAGE`, `SDOK` and `BLINKPHASE`, which keep ble_node's LED and scan state in step.
- **wifi_node → cyd_node:**
  - `W,…` and `B,…` sightings, already geotagged.
  - `SCANSTATE`, `EPOCH`, `GPSPOS`, `SATS` and `CH`.
- **cyd_node → wifi_node:**
  - `SDOK`, `CFG:channelHopMs=…` and `SCANSTATE`.
  - `START`, `OK` and `FAIL` (upload LED states), plus `BLINKPHASE` and `LOWSTORAGE`.

Every link runs at 115200 8N1, one `\n`-terminated line per message.

## Why the CYD link is a wire

It used to be BLE. wifi_node's single radio spends its whole time hopping WiFi channels, and sharing it with a BLE connection made the link drop every 10–20 s. A UART costs nothing.

The CYD end uses **CN1** on UART2 (IO27 RX, IO22 TX). The bottom header is UART0, whose RX line is driven by the CYD's CH340 USB chip, so an external TX can't pull it low reliably. On the ESP32-S3 side, `ARDUINO_USB_CDC_ON_BOOT` moves `Serial` to native USB, which frees all three hardware UARTs:

- UART0 goes to the CYD.
- UART1 goes to the GPS.
- UART2 goes to ble_node.

A wire has no handshake, so "link up" means wifi_node's broadcasts, sent every ~2 s, are still arriving. After 6 s of silence the link counts as down.

## Run state sync

cyd_node is the authority on whether the rig is scanning. It stores that state in flash (NVS), so it survives power loss. It broadcasts `SCANSTATE` immediately on every change and every 2 s regardless. wifi_node adopts it and relays it to ble_node.

wifi_node's idle auto-stop is the only change that starts at the other end. cyd_node adopts it the same way. Whatever order the boards boot in, any disagreement heals within about 2 s.

Only a deliberate START or STOP is persisted. The temporary stop and restart around an upload is not saved, so a power cut mid-upload resumes whatever the user last asked for.

## Phone link

cyd_node runs a NimBLE GATT server named `WardriveCYD`, and mirrors the same lines to USB serial. See [Phone app → Protocol](PHONE_APP.md#protocol-for-writing-your-own-client).

- Outgoing notifications are chunked to the peer's negotiated MTU (capped at 240 bytes).
- During uploads the BLE stack is suspended to free heap, because WiGLE's TLS handshake needs a big contiguous block.

`WD:MESHLINK` is the wdstream field name kept for compatibility. It carries the rig (wired) link state.

## Display quirks

These are for the dual-USB CYD with an ST7789 panel:

- TFT_eSPI's ST7789 init table always sends `INVON`, which inverts the colours. The `TFT_INVERSION_OFF` build flag counteracts it inside `tft.init()`.
- `rotation(0)` is landscape on this panel. `runRotationDebug()` in `src/cyd_node/main.cpp` helps you re-derive it on a different panel.
- The XPT2046 touch controller is bit-banged on its own pins (IRQ 36, MISO 39, MOSI 32, CLK 25, CS 33).
- The display pin map is set entirely by `build_flags` in `[env:cyd_node]` (`USER_SETUP_LOADED`), so updating the library won't overwrite it.
- The board is built as the generic `esp32dev` target with 4 MB flash and the `huge_app.csv` partition layout, which gives one ~3 MB app partition and no OTA.

## Known limitations

- **2.4 GHz only.** The ESP32-S3 can't receive 5 GHz. An ESP32-C5 could.
- **Open BLE link.** Anyone in range can start or stop scanning. Pairing or bonding would fix this.
- **wdgwars upload format.** The request format (`X-Api-Key` header, `file` field) follows common convention and has worked in practice, but it hasn't been checked against wdgwars' logged-in API docs.
- **WiGLE TLS memory.** WiGLE uploads used to fail with a TLS out-of-memory error while BLE was running. BLE is now suspended during uploads; watch the `[upload] free heap` line if it comes back.
- **The SD card is the single point of failure.** If it fails or is missing, sightings are dropped rather than buffered.
- **The SD card is only checked when scanning starts.** A card pulled mid-run fails silently until the next START.
- **Uploads load the whole CSV into RAM.** That's fine for normal runs; stream it instead if files get huge.
- **Security type is a heuristic.** It works from the RSN tag, the WPA vendor tag and the privacy bit, so WPA3 shows as WPA2.
- **No `_nomap` filter in firmware.** The phone app drops these SSIDs, but the rig's own CSVs and uploads include them.
- **No raw pcap capture** in the three-board layout.
- **Touch calibration** was tuned for one unit.
- `CydBleLink.cpp` still contains the unused client (central) role from when wifi_node used BLE.
