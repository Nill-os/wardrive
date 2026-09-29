# Using the rig

## Quick start

1. Put `config.cfg` on the SD card and insert it into the CYD (see [Config](CONFIG.md)).
2. Power up all three boards. The CYD header should show **RIG:UP** within a few seconds.
3. Wait for **GPS:FIX**. The GPS needs sky; the first fix can take a few minutes.
4. Tap **START**. The WiFi and BLE counts start climbing as you drive.
5. When you get home, tap **STOP**. If a home geofence is set, the rig uploads by itself (dock mode). You can also tap **UPLOAD** at any time.

> **No GPS fix means nothing is logged.** Indoors, the counts stay at 0 even while scanning. That's by design: a sighting with no location is useless to WiGLE.

## The screen

A status bar across the top is always visible:

| Indicator | Meaning |
|---|---|
| `GPS:FIX` / `GPS:--` | wifi_node's GPS has or hasn't got a position |
| `CH:06` | The WiFi channel currently being sniffed |
| `STATUS:SCANNING` / `STATUS:STOPPED` | The rig's run state |
| `RIG:UP` / `RIG:WAIT` / `RIG:DOWN` | The wired link to wifi_node (and ble_node behind it). UP means wifi_node's broadcasts are arriving. WAIT shows briefly after RE-LINK ALL. DOWN means nothing has arrived for 6 s. |
| `PHONE:BLE` / `PHONE:USB` / `PHONE:DOWN` | Whether a phone app is connected, and how |

There are five tabs, which you tap along the bottom:

| Tab | What's on it | Buttons |
|---|---|---|
| **1.MAIN** | WiFi and BLE counts for this run, SD and config health, the last upload result, and the latest device seen | **START/STOP**, **UPLOAD**, **RE-LINK ALL** |
| **2.TGTS** | The last 8 WiFi networks seen, with signal and security | **CLEAR** (clears the screen list only, not the logs) |
| **3.LINKS** | Details for the rig link and the phone link | **RE-LINK ALL** |
| **4.LOGS** | A live event terminal | **PAUSE/RESUME LOG**, **FLUSH TO SD** |
| **5.CFG** | SD usage, GPS satellites, firmware info | **WIPE LOGS**, **REBOOT** |

What the buttons do:

- **START/STOP** starts or stops scanning and logging on all three boards. The rig remembers this through power cuts (see below).
- **UPLOAD** uploads every session file that hasn't been uploaded yet, straight away. The phone's BLE link drops during the upload to free memory, and reconnects by itself afterwards.
- **RE-LINK ALL** re-sends the rig state to wifi_node, restarts the CYD's Bluetooth so the phone reconnects fresh, and re-announces itself to the phone. Try this first whenever a link looks stuck.
- **WIPE LOGS** ⚠ deletes **every** session file on the card, including ones not yet uploaded. There is no confirmation. It only works while scanning is stopped.

## Status LEDs

The ESP32-S3 boards use their onboard RGB LED, dimmed to 3% (`LED_BRIGHTNESS_PCT` in each `main.cpp`). The CYD has a plain RGB LED on the back.

| Colour | Meaning |
|---|---|
| Purple flicker (wifi_node) | A WiFi AP was seen |
| Cyan flicker (ble_node) | A new BLE device was seen this run |
| Green (brief) | Scanning started or stopped, or the rig stopped itself after sitting idle |
| Blue (brief, CYD) | UPLOAD tapped |
| Purple (brief, CYD) | RE-LINK ALL tapped |
| Cyan/purple alternating | Upload in progress |
| Green (long) | Upload succeeded |
| Red (long) | Upload failed (no WiFi, no SD or no config), or a refused action |
| White (brief) | The SD card has less than 100 MB free |
| Yellow (long, CYD) | A phone app just connected |
| Red blip every ~3 s (CYD) | RIG:DOWN: nothing is arriving from wifi_node |
| Red blip every ~0.8 s (CYD) | RIG:WAIT after RE-LINK ALL |

Constant flicker in a busy area is normal.

## Car power and power loss

- The rig **remembers whether it was scanning**. Cut the power mid-drive and, when power returns, it resumes scanning by itself. Only the CYD stores this; the other two boards pick it up from the CYD within about 2 seconds of booting.
- Files are flushed to the card as sightings are written, so a power cut loses at most the last sighting or two.
- A file is marked as uploaded only after the server accepts it. A power cut mid-upload just means the file is retried next time.

### Idle auto-stop

If scanning is on and the GPS shows less than 50 m of movement for 30 minutes, wifi_node stops the rig and everything flashes green. This protects a car battery on an always-on USB socket. It only uses a live GPS fix, so a long tunnel or an underground car park won't trigger it.

## Dock-mode uploads

While scanning is stopped, the CYD checks every 60 s whether it should upload:

- **With a home geofence** (`home_lat`, `home_lon`, `home_radius_m`): it uploads once each time you arrive home. If WiFi isn't reachable yet, it retries every 60 s until the upload succeeds or you leave.
- **Without a geofence**: it tries to upload at most once every `min_upload_interval_sec`.

The CYD has no GPS of its own. It uses the position relayed by wifi_node, so dock mode needs RIG:UP and a GPS fix.

## What gets logged

- **WiFi:** 2.4 GHz beacons and probe responses (channels 1–11). The ESP32-S3 can't hear 5 GHz.
- **BLE:** advertisements, including name and manufacturer data.
- **De-duplication:** a WiFi AP is logged again only after you've moved more than about 40 m. A BLE device is logged once per run. Both reset at every START.
- **GPS gaps:** for up to 15 s after losing the fix, the last known position is used, with a worse accuracy value. After that, nothing is logged until the fix returns.
- **Storage:** about 100–130 bytes per row. Even heavy daily urban driving takes years to fill a 32 GB card.
