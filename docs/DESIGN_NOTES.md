# Design notes

These notes explain how the rig works inside, for anyone changing the code.

## Roles

| Board | Radio | Owns | Code |
|---|---|---|---|
| wifi_node | WiFi (promiscuous sniffer, channels 1–11) | GPS, geotagging, WiFi de-duplication, idle auto-stop | `firmware/src/wifi_node/` |
| ble_node | BLE (passive scan) | Per-run BLE de-duplication | `firmware/src/ble_node/` |
| cyd_node | BLE server (phone only) | SD card, `config.cfg`, CSV writer, uploader, touchscreen UI, run state | `firmware/src/cyd_node/` |

Code used by more than one board lives in `firmware/lib/WardriveShared/`:

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

Both board-to-board links run at 460800 8N1 with 4–8 KB UART buffers, one `\n`-terminated line per message. At 115200, each ~120-byte sighting line blocked the sender for about 10 ms, and the CYD's default 256-byte receive buffer overflowed during screen redraws.

## Capture speed

- **Repeat filter in the sniffer callback:** a 512-slot cache drops a BSSID heard in the last 500 ms before it's queued, so repeated beacons can't crowd new APs out of the 128-slot queue. `frames`, `queued` and `qdrop` in wifi_node's heartbeat (and `qdrop` on the CYD) show how it's doing.
- **Channel plan:** wifi_node (which has the external antenna) hops the three non-overlapping, most-used channels **1, 6, 11**, so it revisits each about every 450 ms and catches the bulk of APs on a single pass. The CYD's own sniffer sweeps the full **1–11**, covering the less-common channels. Both hop at `channel_hop_ms` (150 ms, just over one beacon interval).
- **GPS at the module's default 1 Hz** (9600 baud, standard NMEA). An earlier build sent u-blox UBX commands at boot to raise this to 5 Hz, but at 9600 the extra config traffic overran the line and the module never got a clean fix, so it was removed - plain factory-default NMEA is what actually works here. `gga=` in the heartbeat climbs about once a second once there's a fix. No fix indoors is normal; it needs sky/antenna.
- **Positions to the CYD every 500 ms**, so the CYD's own catches are tagged with a recent position.
- **No sightings lost before the first fix:** ble_node only sends (and marks as seen) while wifi_node reports a fix (`GPSFIX:1`), and the BLE controller's duplicate filter is off. Before this, every BLE device heard before the first fix of a run was never logged.
- **USB mirroring only when used:** the CYD copies phone lines to its USB port only while something has sent a USB command in the last 15 s.

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
- `rotation(0)` is landscape on this panel. `runRotationDebug()` in `firmware/src/cyd_node/main.cpp` helps you re-derive it on a different panel.
- The XPT2046 touch controller is bit-banged on its own pins (IRQ 36, MISO 39, MOSI 32, CLK 25, CS 33).
- The display pin map is set entirely by `build_flags` in `[env:cyd_node]` (`USER_SETUP_LOADED`), so updating the library won't overwrite it.
- The board is built as the generic `esp32dev` target with 4 MB flash and the `min_spiffs.csv` partition layout: a ~1.9 MB app partition (the ~1.4 MB app plus the NimBLE stack needs the room; the default layout's ~1.25 MB was too tight). The SPIFFS partition is left tiny since the CYD keeps everything on the SD card. All boards flash over USB - there's no over-the-air update path.

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

## Counts

Every headline number (WIGLE / WDGW / BT) is **unique devices this run**, not CSV rows: an AP re-logged after you move 40 m is a new row but not a new device. The rig keeps a per-run Bloom filter for this (20 KB WiFi, 8 KB BLE, freed when the run stops; about 1% undercount at 8,000 devices, never an overcount). The phone counts the union of rig and phone MACs and exchanges counts with the rig (`app counts`, `rw=`/`rb=`), so the CYD, the app and the Organic Maps overlay show identical figures. The CYD shrinks a number to fit its box and abbreviates past 99,999 (`123K`, `1.2M`).

## Clock and "last upload"

The CYD has no RTC. Its clock is, in order of preference: the phone's wall clock (`app time <epoch>`, sent every status tick while linked) and GPS time relayed by wifi_node (`EPOCH:`), advanced with `millis()` between updates. wifi_node sends GPS time only while the GPS has a **live fix** (`EPOCH:0` otherwise): a module with no fix still reports a date and time that TinyGPS calls valid but that can be days off (seen: 9 days ahead), which once made "last upload" read 9 days. The persisted last-upload time is also rebuilt at boot and after each upload from the newest `<file>.uploaded` marker, and each upload stamps a boot-relative timer, so the main-screen "OK 2h ago" is right even if an upload ran with no clock. The phone's clock also lets the rig run its dock auto-upload (which needs a clock) without a GPS fix. The serial heartbeat prints `clk=`, `lastok=`, `now=` and the screen's upload line for debugging.

## Hidden networks

A hidden (cloaked) network beacons a blank SSID and only reveals its name in probe responses, so the AP de-dup (wifi_node and CYD) lets an AP that was first logged blank through exactly once more when a frame with its name arrives; otherwise the 40 m movement rule would keep the blank row and lose the name. Many networks stay blank for good: in a Comcast-heavy suburb about 60-70% of 2.4 GHz BSSIDs are hidden by design (WPA2-Enterprise hotspot and mesh-backhaul interfaces), not a parsing fault. At boot the CYD also deletes small session files whose rows all lack a position or a real clock (runs that never had a GPS fix).

## Known limitations

- **The rig is 2.4 GHz only.** The ESP32-S3 can't receive 5 GHz (an ESP32-C5 could). The phone app scans the phone's own radio, which covers 2.4, 5 and 6 GHz, so with a phone connected the combined logs cover every band.
- **Upload TLS is pinned to root CAs.** The firmware verifies WiGLE (ISRG Root X1) and wdgwars.pl (GTS Root R4 / GlobalSign Root CA) against the roots in `lib/WardriveShared/RootCerts.h`, so API keys are only sent to the real servers. If a host switches CA, uploads fail with a TLS error until the roots are updated; GlobalSign Root CA expires 2028-01-28.
- **wdgwars upload format** matches wdgwars.pl's documented CSV API (`POST /api/upload-csv`, `X-API-Key` header, multipart `file`, WigleWifi-1.6). Their example header also lists empty `RCOIs` and `MfgrId` columns, which this firmware doesn't write. The server's answer (`imported` / `duplicates` / `no_gps` / `bad_rows` / `cooldown`) is logged on the serial console after each upload, and `{"ok":false}` counts as a failed upload.
- **WiGLE TLS memory.** WiGLE uploads used to fail with a TLS out-of-memory error while BLE was running. BLE is now suspended during uploads; watch the `[upload] free heap` line if it comes back.
- **The SD card is the single point of failure.** If it fails or is missing, sightings are dropped rather than buffered. A card that fails mid-run is now detected within 30 s (red flash, SD:FAIL) rather than failing silently, but the rows during that window are still lost.
- **Security type is parsed from the RSN information element** (AKM suites), so WPA2, WPA3-SAE, WPA3-Enterprise, OWE and WPA2/WPA3 transitional are each identified in the CSV. Only the phone's live `wdstream` mirror collapses WPA3 to `WPA2`, for GhostESP compatibility; the logged data keeps the real type.
- **No raw pcap capture** in the three-board layout.
- **Touch calibration** was measured on one unit; other panels may need `TOUCH_RAW_*` adjusted.

## Scaling to more sniffer nodes

The WiFi channel plan is modular. `wifi_node` builds its channel set from two
build flags (default `NODE_INDEX=0`, `NODE_COUNT=1`):

- **One node** (the default) covers the three popular, non-overlapping channels **1 / 6 / 11**.
- **N nodes:** flash each board with `-D NODE_COUNT=<N> -D NODE_INDEX=<0..N-1>` and the full 2.4 GHz plan **1-13** is split round-robin across them, so each node dwells on fewer channels and revisits them faster (e.g. with `NODE_COUNT=3`: node 0 → 1,4,7,10,13; node 1 → 2,5,8,11; node 2 → 3,6,9,12). Set the flags in `[env:wifi_node]` `build_flags`, or per board in the desktop flasher.

That's the firmware side; the code divides the channels for you. Actually running more than one extra sniffer needs three hardware decisions the current 3-board layout doesn't make for you:

- **Connectivity.** Today it's a point-to-point wired UART chain (each sniffer → `wifi_node` → `cyd_node`). More nodes means either more UARTs into the aggregator, a shared bus, or moving the board-to-board link to **ESP-NOW** (each node broadcasts its sightings, the CYD collects them) - ESP-NOW is the natural fit for an arbitrary number of nodes and is the recommended direction.
- **Aggregation.** All sightings still funnel to `cyd_node` (the only board with the SD card and uploader), so its dedup and CSV writer already handle extra volume; only the transport changes.
- **Power.** Each ESP32 is another ~100-250 mA off the car supply.

Seeed XIAO ESP32-C6/S3 boards work well as add-on nodes (small, cheap, external-antenna variants exist); a C6 or C5 also gets you 5 GHz, which the S3 can't do. `ble_node` and `cyd_node`'s own sniffer are unchanged - they're separate coverage layers on top of whatever `wifi_node`(s) you run.

## Scaling BLE nodes

BLE scales differently from WiFi. BLE advertising uses only three fixed
channels (37/38/39), and a scanning controller already listens across all
three, so there are no channels to divide the way the WiFi sniffers split
1-13. What multiple `ble_node` boards divide is the **reporting load**.

`ble_node` builds its split from two flags (default `BLE_NODE_INDEX=0`,
`BLE_NODE_COUNT=1`, or set per board in the desktop flasher's *BLE nodes*
control):

- **One node** (the default) forwards every device it hears.
- **N nodes:** each board forwards only the slice of the MAC-address space it
  owns (`mac[5] % N == index`), so a single board's UART queue to `wifi_node`
  isn't swamped in a dense area - and queue overflow is what actually drops BLE
  sightings, so spreading the reporting lets the rig log more distinct devices
  per unit time. The last MAC byte is device-specific and evenly spread, so it
  distributes owners well. A device advertises repeatedly (roughly every
  0.1-1 s), so its owning node still catches it on a later advertisement even
  if it misses one; only the owner ever forwards it, so the aggregator sees no
  extra duplicates.

The same transport caveat as the WiFi nodes applies: today each `ble_node`
reaches `wifi_node` over its own wired UART, so several BLE boards means more
UARTs into the aggregator or a move to ESP-NOW. The `ble_node` firmware also
runs on a Seeed XIAO ESP32-C3/S3 (see [Build and flash](BUILD_AND_FLASH.md#building-variations)), which makes small add-on BLE receivers cheap.
