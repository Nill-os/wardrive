# Car wardriving rig (2x ESP32-S3 + 1x CYD)

Three boards work together as a standalone wardriver, independent of your
phone:

- **wifi_node** (ESP32-S3) — sniffs 2.4GHz WiFi (beacons + probe responses)
  and owns the GY-GPS6MV2 GPS module. No SD card or upload logic of its own,
  and no physical button (removed rig-wide - see "Control surface" below) -
  forwards its own WiFi observations, plus every BLE observation ble_node
  relays through it, to cyd_node over a **wired UART link** (see
  "wifi_node ↔ cyd_node link" below), already timestamped/geotagged from its
  own GPS fix. This board runs no Bluetooth at all - its radio is WiFi-only.
- **ble_node** (ESP32-S3) — scans BLE advertisements and streams each
  observation to wifi_node over a wired UART link. No GPS, no SD card, no upload
  logic, no physical button — wifi_node timestamps it and forwards it on
  cyd_node's behalf.
- **cyd_node** (a CYD / ESP32-2432S028 Dual USB "CYD2USB", ST7789 display -
  NOT the ILI9341 the classic single-USB CYD uses) — owns the
  only microSD card and `config.cfg`, runs the WigleWifi CSV writer and the
  uploader, and shows live rig status on its screen, dark-themed and
  landscape, with on-screen START/STOP and UPLOAD touch buttons - the only
  control surface on the rig now (see "Control surface" below). No GPS or
  radio scanning of its own.

cyd_node logs all three boards' captures to its microSD card in
WigleWifi-1.6 CSV format and uploads directly to **wdgwars.pl** and
**WiGLE** over WiFi — no phone involved. Your phone running [Wardrive Bridge](https://github.com/Nill-os/wardrive-bridge) (or WardriveGo)
connects to **cyd_node** over Bluetooth, with cyd_node's USB port as a
fallback - see "Phone link" below; the two systems don't need to talk to each other for the rig
itself to work, which is what makes this rig work identically whether the
phone is in the car or not.

## Control surface

There is no physical button on this rig anymore - it was removed entirely
(it used to be wired in parallel across all three boards). **cyd_node's
touchscreen is now the only control surface**, via two on-screen buttons:

- **START/STOP**: toggle scanning + logging on/off (all three boards).
- **UPLOAD**: immediately upload all not-yet-uploaded session files to
  wdgwars.pl and WiGLE.
- **Automatic ("dock mode")**: while scanning is stopped, cyd_node checks
  every 60s whether it's near home and, if so, uploads pending files.
  **With a geofence configured** (`home_lat`/`home_lon`/`home_radius_m` in
  `config.cfg`, checked against GPS position relayed from wifi_node since
  cyd_node has no GPS of its own), this fires **once per arrival** - the
  away→near-home transition - not on a fixed time cooldown, so getting home
  twice in the same day (two short trips) uploads twice; it's not throttled
  to once per `min_upload_interval_sec` the way a manually-triggered or
  in-session upload still is. A failed attempt (WiFi not joined yet, etc.)
  just retries on the next 60s tick until it succeeds or you leave again.
  **Without a geofence configured**, there's no real "arrival" to detect
  (you'd always be considered "home"), so this falls back to the original
  time-based behavior instead: an upload attempt happens on the normal 60s
  tick, still throttled to once per `min_upload_interval_sec` - set the
  geofence if you want the once-per-arrival behavior described above.
- **A phone running WardriveGo** can also remotely start/stop scanning via
  cyd_node's USB `scan start`/`scan stop` commands - this was never
  touch-button-dependent and still works exactly as before, just from
  cyd_node's USB port now instead of wifi_node's (see "Phone link" below).

Touching START/STOP on cyd_node broadcasts the new state over the wired link
immediately (not just on the next periodic tick); wifi_node adopts it and
relays it onward to ble_node over its own wired link, the same as it always
relayed status. cyd_node is now the rig's authoritative source of scanning
state, having taken over that role from wifi_node now that wifi_node has no
input of its own to originate a change from - see "wifi_node ↔ cyd_node link" below.

## Car power / sudden power loss

Only **cyd_node** persists scanning intent locally (in flash, not RAM) and
resumes on its own once power comes back — no need to touch START again
after the car's engine cuts power, or after unplugging and replugging.
wifi_node and ble_node no longer decide this for themselves at all (see
"Control surface" above): they just start at STOPPED on every boot and wait
to hear cyd_node's own broadcast, which catches them up within a couple
seconds once the link (BLE for wifi_node, the wired link for ble_node) comes
back up. To verify this yourself: touch START on cyd_node's screen, then
pull power from all three boards and reconnect — cyd_node should come back
up already scanning, with `[boot] resuming scan from before power loss` in
its serial log if `WARDRIVE_DEBUG` is on, and wifi_node/ble_node should
catch up to match within a few seconds.

A few things this is specifically designed around:

- **Power cut mid-upload**: only your last deliberate touch is remembered —
  the temporary stop/restart `doUpload()` does internally around an upload
  is never saved, so a power cut during an upload resumes to what you
  actually last asked for, not whatever transient state the upload happened
  to leave things in.
- **Data loss window**: cyd_node flushes both session files to the SD card
  every time an observation is written while scanning (see `WigleWriter`),
  so at most the last observation or two are ever at risk, not the whole
  session.
- **Interrupted upload**: a file is only marked as uploaded after a real
  successful server response, so a power cut mid-upload just means it gets
  retried later (next UPLOAD touch or dock-mode check) — never
  double-counted, never lost.
- **Three boards, self-healing sync**: cyd_node is the authoritative source
  of scanning state now (see "Control surface" above) - it broadcasts its
  own state over the wired link both immediately on every touch and every 2
  seconds regardless, and wifi_node adopts whatever it says and relays it
  onward to ble_node over the wired link, the instant either disagrees -
  regardless of which board booted first, how long another was down, or why
  they drifted apart in the first place. wifi_node's own idle-auto-stop (see
  below) is the one exception that can originate a change from that end
  instead - it broadcasts back to cyd_node the same way, and cyd_node
  adopts it just as readily, since the resync logic doesn't care which
  direction a disagreement came from. Worst case is a ~2-second window
  where a board briefly acts on stale state before the next broadcast
  corrects it; it can never stay wrong indefinitely.

Each board also flickers a status LED to reflect what's happening, so you
can read status without a laptop plugged in. wifi_node/ble_node use their
onboard addressable RGB LED (GPIO48, no wiring needed) at 3% brightness by
default (`LED_BRIGHTNESS_PCT` near the top of each `main.cpp`) — adjust that
constant if it's too dim or too bright on the dash. cyd_node's onboard LED
is a plain (non-addressable) RGB LED with no brightness control available
in software:

| Color | Meaning |
|---|---|
| Green (brief) | single click registered, or wifi_node auto-stopped from sitting idle too long (all three boards) |
| Blue (brief) | double click registered |
| Purple (brief, wifi_node only) | a WiFi AP was just seen |
| Cyan (brief, ble_node only) | a BLE device was just seen |
| Cyan/purple, alternating | upload in progress (all three boards, after a double click) |
| Green (longer flash) | upload finished successfully (all three boards) |
| Red (longer flash) | upload failed, or couldn't even start - e.g. no WiFi, or SD/config not ready (all three boards) |
| White (brief) | cyd_node's SD card is running low on space (all three boards) |
| Yellow (longer flash, cyd_node only) | the phone app just connected over USB - no separate "disconnected" color exists (see "Phone link" below) |
| Red, brief blip repeating every ~3s (cyd_node only) | LINK is DOWN - no BLE connection to wifi_node right now. This is a *periodic* indicator, not a one-shot flash like everything else in this table - it keeps repeating for as long as the link stays down, so a glance at the LED alone (without looking at the screen) tells you the rig is currently unreachable. Silent (no periodic blink at all) while connected, matching the "no LED activity = nothing wrong" convention every other row here already follows. |
| Red, brief blip repeating every ~0.8s (cyd_node only) | LINK is CONNECTING - only in the window right after touching LINK, until a connection is (re)established (see "Control surface" above) |

In a busy area the purple/cyan capture flicker can be almost continuous -
that's activity, not a malfunction. Any of these can cut another one short
if they land close together (all sharing one LED); harmless, just cosmetic.
The two periodic LINK rows above are deliberately a different kind of
signal from every other row (which are all one-shot event flashes,
including on wifi_node/ble_node) - they repeat for as long as the
underlying condition holds, rather than firing once per event.

wifi_node and ble_node have no upload logic of their own, so they only know
to show the orange/green/red upload states because cyd_node relays them
back over the wire - see the wiring table below.

cyd_node's screen shows a dark-themed, landscape dashboard, live-updated
(dirty-tracked - only redrawn regions that actually changed, to avoid
flicker):

- **Status bar**: GPS fix + current WiFi channel (top-left), scanning state
  (top-right), the wired link to wifi_node (bottom-left - "RIG:UP" while
  wifi_node's periodic broadcasts keep arriving, "RIG:DOWN" after 6s of
  silence) and the phone link (bottom-right - "PHONE:BLE", "PHONE:USB" or
  "PHONE:DOWN").
- **WiFi/BLE stat cards**: this-run counts, purple-accented for WiFi, cyan
  for BLE.
- **SD/config health dots**, and the last upload result when there's been one.
- **Event ticker**: the most recently seen WiFi AP or BLE device name, cyan
  for WiFi (from wifi_node directly) or purple for BLE (relayed one hop
  further, from ble_node through wifi_node) - a quick "who just saw what"
  glance.
- **On-screen touch buttons** (START/STOP, UPLOAD, RE-LINK ALL) - the only
  control surface on the rig. RE-LINK ALL re-sends cyd_node's state to
  wifi_node right away, restarts cyd_node's BLE stack so the phone reconnects
  fresh, and re-announces the phone handshake. The LINKS tab shows both
  links in more detail.

## Capture behavior

- **Per-device, per-run dedup**: sitting still (a red light, a stop sign)
  means the same AP or BLE device gets captured many times a second — never
  logged/sent more than once for the same run at the same spot, no matter
  how long you sit there. wifi_node's dedup is *distance*-based (not
  time-based): a given BSSID only logs again once you've moved more than
  ~40m from where it was last logged (`AP_DEDUP_MOVEMENT_THRESHOLD_M`), so
  looping back past the same AP a block away still logs fresh from its new
  position, and true duplicates never creep back in just because a timer
  ran out while you're stopped. ble_node has no GPS to judge movement (and
  BLE devices are often mobile themselves anyway), so its dedup is a
  straight per-run seen-set: each MAC is sent once per run, full stop. Both
  dedup states reset completely at the start of every new run — duplicates
  across two separate runs are expected and fine, only *within* one run do
  they get suppressed. The LED still flickers on every single detection
  either way, so you always see live activity even when the row itself is
  being skipped as a duplicate. These are fixed constants near the top of
  each `main.cpp`, not `config.cfg` settings.
- **GPS gap tolerance**: losing the GPS fix for a few seconds (a tunnel, a
  parking garage, tree cover) no longer means losing that stretch of road
  entirely. wifi_node falls back to the last known-good position for up to
  15 seconds (`GPS_GAP_TOLERANCE_MS`) before it starts refusing rows again —
  long enough to cover a brief gap, short enough that a real dead GPS
  doesn't get everything mis-pinned to one stale spot. Rows logged this way
  get a worse accuracy estimate on purpose, since the position is
  interpolated, not live.
- **Low-storage warning**: cyd_node checks free space on the SD card every
  30 seconds and flashes white on all three boards (`LOWSTORAGE` relayed
  over both status links) once free space drops under 100MB
  (`LOW_STORAGE_THRESHOLD_BYTES`), so it gets noticed well before writes
  start silently failing.
- **Idle auto-stop (power-saving)**: this rig is meant to run unattended for
  weeks on car power that may not be ignition-switched — if scanning gets
  left on after you park and walk away, all three boards' radios keep
  drawing current indefinitely, which is a real battery-drain risk on an
  always-on 12V circuit. If GPS shows no movement past 50m for 30 minutes
  (`IDLE_TIMEOUT_MS` / `IDLE_MOVEMENT_THRESHOLD_M`) while scanning, wifi_node
  stops itself and broadcasts that to the other two boards (green flash on
  all three) - cyd_node adopts it and is the one that actually persists it
  to survive a power cycle, same as it does for any other change of intent.
  This check only ever runs off a *live* GPS fix, never the
  gap-tolerance fallback above — a real signal dropout (underground
  parking) must never be mistaken for "parked and idle" and shut the rig
  off mid-tunnel.
- **Old file cleanup (off by default)**: `retention_days` in `config.cfg`
  defaults to 0 — keep every uploaded session file on the card forever, as a
  standing local backup. At realistic driving volumes this never comes
  close to filling a normal card (see the sizing estimate below), so
  there's no real reason not to keep everything. Set it to a nonzero value
  if you ever want old uploaded files cleaned up automatically after that
  many days instead — checked once a day while idle, and never based on a
  guess (if cyd_node's clock isn't known yet - it has no GPS of its own, so
  this depends on wifi_node's relayed epoch having arrived at least once -
  the check is skipped entirely rather than risking an early delete).

  **Storage sizing, for reference**: a WigleWifi-1.6 row is roughly
  100-130 bytes. With the dedup above, realistic capture rates run
  ~500-1,000 rows/hour in light suburban driving up to ~10,000-20,000
  rows/hour in dense urban areas — so even 8 hours/day of dense-urban
  driving, forever, with retention off, takes on the order of a decade to
  fill a 64GB card. The one scenario that actually matters here is uploads
  silently failing for a long stretch (WiFi password changed, an API key
  expired) — with retention off, nothing ever gets cleaned up during that
  window, but the low-storage warning above exists specifically to catch
  that well before it becomes a real problem.

## Hardware / wiring

Only **cyd_node** carries a microSD card - wifi_node and ble_node stay
fileless and stream their scan results onward for cyd_node to write and
upload. Only **wifi_node** carries the GPS module.

Every pin, not bundled:

| Pin (as printed on the part) | wifi_node pin | ble_node pin | cyd_node pin | Notes |
|---|---|---|---|---|
| GPS — VCC | 3V3 | — | — | GY-GPS6MV2, 9600 baud NMEA |
| GPS — TX | GPIO17 (RX) | — | — | |
| GPS — RX | GPIO18 (TX, optional) | — | — | only needed if you reconfigure the GPS module |
| GPS — GND | GND | — | — | joins the common ground bus |
| BLE observation link | GPIO8 (RX, in) | GPIO8 (TX, out) | — | ble_node streams `MAC,RSSI,NAME,MFG_HEX` lines to wifi_node here, 115200 baud |
| BLE status link | GPIO3 (TX, out) | GPIO3 (RX, in) | — | wifi_node streams `START`/`OK`/`FAIL`/`STOP`/`LOWSTORAGE`/`SDOK`/`BLINKPHASE` lines to ble_node here, so its LED and scanning state stay in step, 115200 baud |
| cyd_node link — data to cyd_node | GPIO13 (TX, out) | — | CN1 IO27 (RX, in) | 115200 baud, see "wifi_node ↔ cyd_node link" below |
| cyd_node link — data to wifi_node | GPIO14 (RX, in) | — | CN1 IO22 (TX, out) | leave CN1's 3.3V pin unconnected |
| SD, touch, TFT | — | — | on-board, fixed | the CYD's SD slot (GPIO5/23/19/18), touch controller (XPT2046: IRQ 36, MISO 39, MOSI 32, CLK 25, CS 33), and 2.8" ST7789 display are all wired on the PCB itself - see `platformio.ini`'s `[env:cyd_node]` build_flags for the exact display pin map. Verify against your specific board's silkscreen/seller listing; sub-revisions can still wire things slightly differently. |

There's no physical button anymore - GPIO4 (wifi_node/ble_node) and GPIO35
(cyd_node) are all free and unused now; cyd_node's touchscreen is the only
control surface (see "Control surface" above).

All three boards need a common ground (share the car's 5V/USB power rail's
GND) for the observation-link wiring to work.

## wifi_node ↔ cyd_node link

A wired UART at 115200 baud: wifi_node's GPIO13/GPIO14 to cyd_node's CN1
header (IO27/IO22) plus a common ground - see the wiring table above. It
used to be Bluetooth, but wifi_node's radio is busy hopping WiFi channels
the whole time it scans, and sharing it with a BLE link made the link drop
every 10-20 seconds mid-run. A wire has no such cost.

- **Line protocol**: `W,...`/`B,...` sightings, `SCANSTATE`, `EPOCH`,
  `GPSPOS`, `SATS` and `CH` from wifi_node; `SDOK`, `CFG`, `SCANSTATE`,
  `START`/`OK`/`FAIL`, `BLINKPHASE` and `LOWSTORAGE` from cyd_node. One
  `\n`-terminated line per message.
- **Link health**: a wire has no connection handshake, so cyd_node treats
  the link as up while wifi_node's broadcasts (every ~2s) keep arriving,
  and down after 6s of silence.
- **`SCANSTATE` flows both ways**: cyd_node broadcasts it on every touch or
  phone command and every 2s regardless; wifi_node adopts it and relays it
  to ble_node. wifi_node's idle-auto-stop can also originate a change.
- **No GPS fix, no sightings**: wifi_node only forwards a sighting once it
  has a position (a live fix, or one less than 15s old), so indoors with no
  fix the counts stay at 0 even while scanning.

## Firmware config

`config.cfg` now lives on **cyd_node's** SD card (wifi_node and ble_node
never read one):

```
wifi_ssid=YourHomeWifi
wifi_pass=yourpassword
backup_wifi_ssid=YourPhoneHotspot
backup_wifi_pass=yourhotspotpassword
wigle_api_token=BASE64_ENCODED_APINAME_COLON_APITOKEN
wdgwars_api_key=YOUR_64_HEX_CHARACTER_WDGWARS_KEY
min_upload_interval_sec=21600
home_lat=37.774900
home_lon=-122.419400
home_radius_m=150
retention_days=0
channel_hop_ms=150
```

- `backup_wifi_ssid` / `backup_wifi_pass` — optional second network (e.g.
  your phone's hotspot) that upload falls back to if `wifi_ssid` can't be
  joined. Tried only after `wifi_ssid` times out, so it never delays a normal
  home-WiFi upload. Leave blank to only ever try `wifi_ssid`.
- `wigle_api_token` — from https://wigle.net/account, use the "Encoded for
  Use" credential (already base64-encoded `apiName:apiToken`, ready to drop
  straight into a `Basic` auth header). Leave blank to skip WiGLE and only
  upload to wdgwars.pl.
- `wdgwars_api_key` — generate at https://wdgwars.pl/profile ("Generate API
  key"), 64 hex characters.
- `min_upload_interval_sec` — dock-mode auto-upload cooldown, in seconds
  (21600 = 6 hours). Double-click always uploads immediately regardless of
  this cooldown.
- `home_lat` / `home_lon` / `home_radius_m` — optional GPS geofence for
  dock-mode auto-upload. Set `home_radius_m` to 0 or omit all three to
  disable the geofence (falls back to WiFi-visibility-only gating).
- `retention_days` — how long an already-uploaded session file (and its
  `.uploaded` sidecar) stays on the SD card before cyd_node deletes it,
  checked once a day. Defaults to 0 (keep uploaded files forever, as a
  standing local backup) — see the "Capture behavior" section above for why
  that's the recommended default. Set it to a nonzero value only if you
  actually want automatic cleanup.
- `channel_hop_ms` — how long wifi_node's promiscuous sniffer dwells on each
  2.4GHz channel (1-11) before hopping to the next. Defaults to 150ms; lower
  catches more distinct APs per minute at highway speed, higher favors
  catching weaker/slower-advertising devices. Loaded by cyd_node and
  forwarded to wifi_node over the link (`CFG:channelHopMs=...`), since
  wifi_node no longer has its own SD card to read `config.cfg` from
  directly.

**`pcap_capture` (raw .pcap capture) is currently unsupported** in the
three-board layout - it depended on wifi_node's own SD card, which moved to
cyd_node. Streaming full raw 802.11 frames over the new wifi_node↔cyd_node
UART link (rather than just the summarized WigleWifi CSV path) is a bigger
protocol change than this pass covered; revisit if you need it back.

**Please double check the wdgwars.pl request format once you're logged in**
(Help section on the site) before relying on the upload. I could only reach
wdgwars.pl's public pages, not the authenticated API docs, so
`uploadToWdgwars()` in `lib/WardriveShared/Uploader.cpp` currently assumes
an `X-Api-Key` header and a `file` multipart field — the most common
convention, but unverified against their real spec. If it's not accepted,
that's the one place to fix.

## Building and flashing

```
pio run -e wifi_node -t upload -t monitor
pio run -e ble_node  -t upload -t monitor
pio run -e cyd_node  -t upload -t monitor
```

### Testing the BLE link without a phone

`tools/test_ble_link.sh` drives cyd_node's BLE link from this machine's own
Bluetooth adapter (via `bluetoothctl`/`gatttool`), standing in for the phone -
useful for sanity-checking a `CydBleLink.cpp`/cyd_node change. It was written
when this link served wifi_node, so its example lines may need updating to
phone commands (`wdstream start`, `scan start`):

```
tools/test_ble_link.sh scan              # confirm WardriveCYD is advertising
tools/test_ble_link.sh attrs             # dump its GATT service/characteristics
tools/test_ble_link.sh notify 8          # subscribe to the TX characteristic for 8s, print what arrives
tools/test_ble_link.sh send "SCANSTATE:0"  # write a line to the RX characteristic
```

Needs `bluez` installed and the adapter unblocked/powered
(`rfkill unblock bluetooth` if `rfkill list bluetooth` shows it soft-blocked -
block it again afterward if you want the radio back the way it was).

wifi_node/ble_node target a generic `esp32-s3-devkitc-1` board — adjust
`platformio.ini` if your specific dev boards need a different board ID.

cyd_node targets the generic `esp32dev` board (with an explicit 4MB flash
size) rather than a dedicated board definition for this exact variant, since the
installed `espressif32` platform version doesn't have one yet — the CYD is
a plain ESP32-WROOM-32 module underneath, so this is the standard community
workaround. If a future platform update adds a matching
board, switching to it is a one-line change in `[env:cyd_node]`.

cyd_node also uses the `huge_app.csv` partition scheme (one ~3MB app
partition) instead of the default dual-OTA-slot layout - adding the NimBLE
stack for the wifi_node link left almost no headroom under the default
~1.25MB app partition, and this board never receives OTA updates anyway, so
trading the second OTA slot for space was a clear win.

`platformio.ini` sets `ARDUINO_USB_MODE=1` and `ARDUINO_USB_CDC_ON_BOOT=1`
for wifi_node/ble_node only. Those two ESP32-S3 boards have no separate
USB-to-UART bridge chip — only the ESP32-S3's native USB peripheral.
Without these flags, Arduino's `Serial` silently maps to a disconnected
hardware UART instead of that native USB port, so none of your own
`Serial.print()` output would ever reach the monitor (ESP-IDF's own
internal error logging uses a different path and would still show up,
which makes the missing output easy to mistake for a hang). Don't remove
these flags. cyd_node is a plain ESP32 with a real USB-UART bridge chip, so
none of this applies there.

## Phone link (wdstream)

The phone talks to **cyd_node** over a plain-text line protocol
(reverse-engineered from GhostESP's own firmware source, and understood by
WardriveGo's "Cerberus" mode). **BLE is the primary transport**: cyd_node
advertises as `WardriveCYD` with a NUS-style RX/TX characteristic pair
(`5b60de00-...`, see `CydBleLink.cpp`). **Its USB port is the fallback** -
every phone-bound line goes out over both. cyd_node is a plain ESP32
with a real USB-UART bridge chip, a better fit for a phone-side serial app
than wifi_node's/ble_node's native-USB ESP32-S3s (see "Building and
flashing" above for why those two need `ARDUINO_USB_MODE`/
`ARDUINO_USB_CDC_ON_BOOT`). This runs entirely alongside the normal WigleWifi
CSV/upload pipeline, never replacing it.

Commands the phone can send (one per line):

- `wdstream start` — begin mirroring scan activity as `WD:`-prefixed lines.
  Over BLE the connection itself tells cyd_node a phone is present; over
  USB the phone sends `wdstream status` every few seconds as a keep-alive.
- `wdstream stop` — stop the mirror.
- `wdstream status` — emit one `WD:STATUS` line on demand.
- `scan start` / `scan stop` — remotely drive the rig's actual scanning,
  same effect as touching START/STOP on cyd_node's screen.

cyd_node emits `WD:AP`/`WD:BLE` lines as it receives wifi_node's `W,`/`B,`
lines (not at the moment of the actual radio hit - this board never sees
that itself, only wifi_node's already-relayed sighting, so there's a couple
hundred ms of extra latency versus sniffing at the source), plus `WD:STATUS`
every 2s while active and `WD:SCANSTATE:<0|1>` on every change. Everything
else on that USB port is normal debug output - the phone app is expected to
ignore any line that isn't `WD:`-prefixed.

## Debugging over serial

All three boards print a `[heartbeat]` line every 2 seconds when
`WARDRIVE_DEBUG` (top of each `main.cpp`) is `true` — uptime, whether it's
currently scanning, GPS fix status (wifi_node), SD status (cyd_node), and
how many lines have arrived over each link. Touch/upload events and
boot-time SD/config status also print. If the heartbeat is ticking,
the board is not hung, whatever else might be going wrong. Flip
`WARDRIVE_DEBUG` to `false` once the rig is proven out, to quiet the serial
output down.

## Known limitations / things to revisit

- **WiGLE uploads were failing with a TLS memory error** - a real upload
  this morning showed every WiGLE POST failing with `RSA - The public key
  operation failed : BIGNUM - Memory allocation failed` (wdgwars.pl
  succeeded for the same files every time) - consistent with NimBLE's own
  heap usage leaving too little contiguous free heap for WiGLE's TLS
  handshake specifically. **Fix applied**: `doUpload()` now calls
  `CydBleLink::suspendServer()` before `uploadPending()` and
  `resumeServer()` after, freeing that RAM for the exact window that needs
  it - a phone connected over BLE drops during an upload and reconnects on
  its own afterward (the wired wifi_node link is unaffected), and the upload
  path already blocks the whole main loop for its duration regardless, so
  nothing is actually being served over BLE during that window anyway. A
  `[upload] free heap before POST ...` line was added to
  `Uploader.cpp` to make this visible going forward. **Not yet re-verified
  against a real repeat upload** - reproducing it needed either a real
  upload (blocked on the dock-mode geofence needing real GPS coordinates
  this session didn't have) or the physical/touch UPLOAD button, neither
  available remotely. Watch the free-heap line and the WiGLE POST result on
  the next real upload to confirm.
- **cyd_node's BLE link to the phone is open and unauthenticated** - anyone
  within BLE range could connect and send phone commands (`scan start`/
  `scan stop`, `wdstream ...`), i.e. start or stop your scanning. It can't
  inject sightings into your logs (those only arrive over the wire from
  wifi_node) and it carries no credentials. Adding pairing/bonding would
  close this.
- **wdgwars.pl auth format is unverified** (see above) — check it against
  your account's docs.
- **SD card isn't continuously monitored while scanning** — the live
  writability check only runs at the moment scanning starts (flashes red if
  it fails). If the card is pulled or fails mid-session, writes just start
  silently no-op'ing rather than triggering a fresh red flash until the
  next start. Full hot-swap detection during active sniffing would add
  meaningful SPI overhead to the capture hot path for a rare failure mode,
  so it's out of scope for now.
- **Single point of failure**: since wifi_node and ble_node hold no files of
  their own, cyd_node's SD card gates all logging - if its card fails or
  has no config, both other boards' observations are simply dropped rather
  than buffered anywhere. Similarly, cyd_node's dock-mode/nearHome() gating
  depends on wifi_node's relayed GPS position - if that link is down,
  cyd_node has no fix to gate on and dock-mode auto-upload won't trigger.
- **`pcap_capture` is currently unsupported** - see the Firmware config
  section above.
- **This ST7789 panel needed non-standard display config** - discovered
  during bring-up:
  - **Colors were inverted** (dark backgrounds rendering as near-white,
    accents as their complements). Not caused by an application-level
    `tft.invertDisplay(true)` call (one was tried and removed early on,
    which turned out to do nothing either way) - the real cause is that
    **TFT_eSPI's own ST7789 init table unconditionally sends the
    `ST7789_INVON` command inside every `tft.init()` call**
    (`TFT_Drivers/ST7789_Init.h`), completely independent of any
    application-level call, which is why removing that call alone never
    fixed it. Fixed correctly via the `TFT_INVERSION_OFF` build flag (now
    set in `[env:cyd_node]`'s `build_flags`) - the library checks for this
    flag and sends the counteracting `TFT_INVOFF` right after that same
    init table, still inside `tft.init()`, which is the actual supported
    mechanism for this, not a runtime call after the fact.
  - **`rotation(0)`, not `1` or `3`, is this panel's correct landscape
    orientation** - see `runRotationDebug()` in `src/cyd_node/main.cpp` if
    you ever need to re-derive this (e.g. after a display hardware
    change).

  Both are settled for the exact unit this was built against; a different
  physical unit of the "same" board could theoretically differ.
- **cyd_node's touch calibration is a starting guess, not verified against a
  real unit** - this matters more now than it used to: the physical button
  is gone, so a miscalibrated touch is the *only* way to control the rig
  being wrong, not just a redundant input degrading. The touch controller
  (XPT2046) is bit-banged in software
  (see the pin/protocol comment in `src/cyd_node/main.cpp`) and is confirmed
  working (raw readings register correctly on real hardware), but the raw
  ADC-to-screen-coordinate mapping constants (`TOUCH_RAW_*`,
  `TOUCH_SWAP_XY`, `TOUCH_INVERT_X/Y`) haven't been properly tuned yet - an
  attempted live calibration pass produced ambiguous data (taps drifted/
  dragged rather than being clean discrete corner presses). If on-screen
  taps land in the wrong place, watch the `[touch] raw x=... y=...` serial
  line while tapping each of the four corners **briefly and separately**
  (lift your finger fully between taps) and adjust those constants to match.
- **cyd_node board ID is a generic `esp32dev` fallback**, not a dedicated
  matching board definition - see "Building and flashing" above.
- **ESP32-S3 is 2.4GHz-only** — no 5GHz WiFi scanning (that would need an
  ESP32-C5). Fine for most home/consumer APs, which still broadcast on
  2.4GHz.
- **Upload buffers the whole CSV file in RAM** before POSTing. This is fine
  for typical session sizes but if cyd_node goes a very long time between
  uploads and accumulates a huge file, switch `postMultipartFile()` in
  `Uploader.cpp` to stream instead of buffering (use a PSRAM-enabled board
  if you increase upload frequency requirements).
- **Auth-mode detection is a simplified heuristic** (RSN tag → WPA2, WPA
  vendor tag → WPA, privacy bit only → WEP, else Open) rather than a full
  cipher/AKM parse — good enough for WiGLE/wdgwars purposes but won't
  distinguish WPA3 from WPA2.
- Rows are only logged when GPS has a valid fix (no interpolation for
  tunnels/parking garages yet) — simplest correct behavior, matching how
  several reference wardriving projects (Hak5 Pineapple Pager's wdgwars
  payload, GhostESP) handle it.

## Related

- [Wardrive Bridge](https://github.com/Nill-os/wardrive-bridge) - the Android companion app (BLE/USB link to cyd_node, phone scanning, logs, uploads).
