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
  - `EPOCH`, `SATS` and `CH` every 2 s, `GPSPOS` every 1 s, and `IDLESTOP` when idle auto-stop fires.
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

wifi_node never sends its own scan state back; it only relays cyd_node's. The one exception is idle auto-stop, which wifi_node reports as an `IDLESTOP` event, and cyd_node applies like a local STOP. So whatever order the boards boot in, they converge on cyd_node's saved state within about 2 s.

Only a deliberate START or STOP is persisted. The temporary stop and restart around an upload is not saved, so a power cut mid-upload resumes whatever the user last asked for.

## Phone link

cyd_node runs a NimBLE GATT server named `WardriveCYD`, and mirrors the same lines to USB serial. See [Phone app → Protocol](PHONE_APP.md#protocol-for-writing-your-own-client).

- Outgoing notifications are chunked to the peer's negotiated MTU (capped at 240 bytes).
- **Pairing:** LE Secure Connections with bonding and MITM protection. The rig is `DISPLAY_ONLY`, so it shows a 6-digit passkey that the phone types in. The passkey is random, only shown during a PAIR PHONE window, and changed when the window closes. Both characteristics need an encrypted, authenticated link (`READ_ENC|READ_AUTHEN` on TX, `WRITE_ENC|WRITE_AUTHEN` on RX), and any connection that hasn't secured itself within 10 s (60 s during a window) is dropped. The app saves the paired rig's address and scans by address from then on.
- During uploads the BLE stack is suspended to free heap, because WiGLE's TLS handshake needs a big contiguous block.

`WD:MESHLINK` is the wdstream field name kept for compatibility. It carries the rig (wired) link state.

## Display quirks

These are for the dual-USB CYD with an ST7789 panel:

- TFT_eSPI's ST7789 init table always sends `INVON`, which inverts the colours. The `TFT_INVERSION_OFF` build flag counteracts it inside `tft.init()`.
- `rotation(0)` is landscape on this panel. `runRotationDebug()` in `src/cyd_node/main.cpp` helps you re-derive it on a different panel.
- The XPT2046 touch controller is bit-banged on its own pins (IRQ 36, MISO 39, MOSI 32, CLK 25, CS 33).
- The display pin map is set entirely by `build_flags` in `[env:cyd_node]` (`USER_SETUP_LOADED`), so updating the library won't overwrite it.
- The board is built as the generic `esp32dev` target with 4 MB flash and the `huge_app.csv` partition layout, which gives one ~3 MB app partition and no OTA.

## Testing without GPS

cyd_node accepts developer commands over **USB serial only** (115200 baud; they're ignored over BLE). They make it possible to test logging and uploads at your desk:

| Command | What it does |
|---|---|
| `test:status` | Prints scan state, tab, test mode, pending uploads, links and free heap |
| `test:fakegps <lat> <lon>` / `test:fakegps off` | A pretend GPS fix, only while stopped. Everything logged in this mode goes to `/wardrive_test`, which normal uploads never touch. |
| `test:move <metres>` | Moves the fake fix north, to exercise de-duplication |
| `test:bigfile <rows>` | Writes a synthetic CSV into `/wardrive_test` |
| `test:uploadhost <url>` | In test mode, sends uploads to your own server instead of wdgwars and WiGLE |
| `test:dedupfill <n>` | Adds fake de-dup entries, to check the memory cap |
| `test:ls` | Lists the current session folder |
| `test:upload`, `test:link`, `test:clear`, `test:pause`, `test:flush`, `test:wipe` | Same as the matching on-screen buttons (`test:wipe` only wipes the current folder, so `/wardrive_test` while in test mode) |
| `test:tab <0-3>` | Switches tabs |

`scan start`, `scan stop` and the `wdstream` commands work over USB too.

## Known limitations

- **2.4 GHz only.** The ESP32-S3 can't receive 5 GHz. An ESP32-C5 could.
- **Uploads don't verify TLS certificates.** The firmware has no root CA bundle configured, so on an untrusted network (a public hotspot) a man-in-the-middle could read your upload keys. Upload over your own WiFi.
- **wdgwars upload format.** The request format (`X-Api-Key` header, `file` field) follows common convention and has worked in practice, but it hasn't been checked against wdgwars' logged-in API docs.
- **WiGLE TLS memory.** WiGLE uploads used to fail with a TLS out-of-memory error while BLE was running. BLE is now suspended during uploads; watch the `[upload] free heap` line if it comes back.
- **The SD card is the single point of failure.** If it fails or is missing, sightings are dropped rather than buffered.
- **The SD card is only checked when scanning starts.** A card pulled mid-run fails silently until the next START.
- **Security type is a heuristic.** It works from the RSN tag, the WPA vendor tag and the privacy bit, so WPA3 shows as WPA2.
- **No `_nomap` filter in firmware.** The phone app drops these SSIDs, but the rig's own CSVs and uploads include them.
- **No raw pcap capture** in the three-board layout.
- **Touch calibration** was measured on one unit; other panels may need `TOUCH_RAW_*` adjusted.
