# Phone app, map overlay and Android Auto

The rig works on its own, and **the phone is optional**. With the phone you get:

- a live dashboard and history
- the phone's own WiFi, BLE and cell scanning, merged in with the rig's
- **live tools** (WiFi/Bluetooth tabs) - Live WiFi, Live BLE, Pineapple, AirTag/Flipper/Flock/skimmer/drone/mesh/glasses/action-cam/police-cam detection. Opening a tool starts scanning for it right away (no run needed); the list pulls from **both the rig and the phone**, and works indoors with no GPS fix.
- **fox-hunt**: long-press any device in a tool to track its live signal (hot/cold + rising beep) and home in on it
- an **antenna checker** (Tools → Antenna check) that reads a chosen device's live signal with peak hold and A/B capture - pick **which radio** to measure (Rig WiFi, Rig BLE, Phone WiFi, Phone Bluetooth, or strongest) so you can test the rig's own antenna
- tracker and skimmer detection alerts
- a **device watchlist** - list specific WiFi/BLE devices (by MAC or name) and get a notification when one comes into range or leaves, each direction toggleable
- start/stop from the phone, the map and the car screen

## Nill OS - Wardriver (Android)

Source: [`android/`](../android) in this repo. It needs Android 8.0 or newer.

### Install

Build it yourself (a signed release APK may be added later):

```
git clone https://github.com/Nill-os/wardrive.git
cd wardrive/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17 and the Android SDK. Android Studio installs both; `local.properties` points Gradle at the SDK.

### First run

1. Grant **Location** (choose **Precise** - Bluetooth scanning withholds results with only Approximate, and select "Allow all the time" if you want it scanning with the screen off), **Nearby devices** (Bluetooth) and **Notifications**.
2. Open **Settings**:
   - **Background:** tap **ALLOW BACKGROUND USAGE** so Android's battery optimizer doesn't pause scanning when the screen is off or the app is backgrounded.
   - **Upload credentials:** your WiGLE "Encoded for use" token and your wdgwars API key. These are only needed if the phone should upload its own logs.
   - **Trip mode** (off by default): for trips away from home WiFi. While on, the phone pulls the rig's own log files over the Bluetooth link and uploads them with your keys over whatever connection the phone has (cell or WiFi); the rig holds off its own upload, so nothing is sent twice. Off = the rig uploads over its own WiFi (`config.cfg`) as usual.
   - **Home exclusion zone:** tap **USE CURRENT GPS FIX AS HOME** while at home, and set a radius (for example 300 m). Anything seen inside it is never logged, exported or uploaded (your home stays private), but still shows briefly in the live tools so fox-hunt and antenna check work at home. Saving also **pushes this zone to the rig** so its own logs match.
   - **CYD counters:** a toggle switches the rig screen's headline numbers between **WiGLE / WDGW** and raw **found APs / Bluetooth**.
   - **Blacklists:** any MACs or SSIDs you never want logged, such as your own devices.
   - Tap **SAVE**.
3. The **Tools** tab (bottom nav) holds everything else: the live WiFi/Bluetooth feeds and detections, **Antenna check** (compare antennas on a live signal, pick which radio), **Watchlist** (devices to be notified about when they enter/leave range, with a toggle for each direction), Floor Plan and Browse Logs.
4. **Pair with your rig (once).** On the rig, open the **4.CFG** tab and tap **PAIR PHONE**. A 6-digit code appears for 60 seconds. With the app open, Android asks you to pair with *WardriveCYD*: type the code and tap OK. The dashboard shows `CYD: PAIR` until this is done.

From then on the phone connects to **that rig only**, and the rig only accepts phones it has paired with. Two rigs and two phones in the same car park never cross over. To move the phone to a different rig, use **Settings → FORGET THIS RIG**. To remove every phone from a rig, use **FORGET PHONES** on its CFG tab.

The dashboard shows two link lines:

- `CYD: BLE` / `CYD: USB` / `CYD: DOWN`: the phone-to-CYD link. `CYD: PAIR` means no rig has been paired yet.
- `RIG: UP` / `RIG: WAIT` / `RIG: DOWN`: the CYD's wired link to the scanner boards, as the CYD reports it.

### USB fallback

If Bluetooth isn't available, plug the phone into the CYD's USB port with a USB-OTG adapter and accept the USB permission prompt. The app switches back to BLE automatically when BLE returns.

### Privacy

- The home exclusion zone and blacklists keep matching devices out of everything the **app** logs, exports and uploads, from both the rig and the phone. They still appear briefly in the live tools (so fox-hunt/antenna check work at home) but are never stored or sent.
- SSIDs ending in `_nomap` are always dropped.
- Saving the exclusion zone **pushes it to the rig too** (home lat/lon + radius over Bluetooth), so the rig's own SD logs and uploads use the same zone. See [Config → Keep it private](CONFIG.md#keep-it-private).
- **No GPS fix:** devices are still gathered (the dashboard shows an amber `NO GPS FIX` warning), but saved without a position and **never uploaded or exported**, so nothing lands at 0,0. If a device is seen again once there's a fix, it's re-logged with a real position and that row is what uploads.

## Organic Maps overlay (optional)

A lightly patched Organic Maps build ([organic-maps-overlay/](../organic-maps-overlay)) shows a one-line Nill OS - Wardriver status on the map, for example:

```
ON · WIGLE 12 · WDGW 9 · BT 30 · CYD BLE · Rig ✓
```

Tap the overlay to open Nill OS - Wardriver. The overlay only **reads** status from Nill OS - Wardriver through a content provider; it never scans or talks to the rig itself, so the two apps can't conflict.

The provider only answers the approved Organic Maps build. It checks the caller's package name **and** the SHA-256 of its signing certificate (`ALLOWED` in `BridgeProvider.kt`). If you build Organic Maps yourself, put **your** certificate's SHA-256 there:

```
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

Use the hex digits in lowercase, without colons.

## Android Auto

Both apps show up on the car screen:

- **Nill OS - Wardriver** has its own car screen, with the live counts and link status.
- **Organic Maps** (the patched build) shows the same status line during navigation. The navigation action strip gets a **record** button that starts and stops the run.

The overlay text on the car map isn't tappable, because Android Auto doesn't allow taps on map drawings. Use the record button, or open Nill OS - Wardriver from the car launcher.

Sideloaded apps are hidden from Android Auto by default. To show them:

1. Open Android Auto's settings.
2. Tap the version number repeatedly until developer mode unlocks.
3. In the developer settings, enable **Unknown sources**.

## Protocol (for writing your own client)

The CYD speaks a line-based text protocol over BLE, and over USB serial at 115200 baud.

- **BLE:** the device is named `WardriveCYD`, with service `5b60de00-0000-4a6c-9b1a-6364796477b1`. Both characteristics need an encrypted, authenticated (bonded) link. The advertisement carries manufacturer data `FF FF 57 44 <1|0>` (company ID 0xFFFF, "WD", then 1 while a pairing window is open).
  - **Write** lines to characteristic `5b60de00-0001-4a6c-9b1a-6364796477b1`.
  - **Subscribe** to notifications on `5b60de00-0002-4a6c-9b1a-6364796477b1`.
  - Request a large MTU (the app uses 247). Lines are split to fit it.
- **Commands** are newline-terminated:
  - `wdstream start`, `wdstream stop` and `wdstream status`.
  - `scan start` and `scan stop` behave like tapping START/STOP.
- **Output:** lines prefixed with `WD:`, namely `WD:BEGIN`, `WD:AP,…`, `WD:BLE,…`, `WD:STATUS,…`, `WD:SCANSTATE:<0|1>` and `WD:MESHLINK:…` (rig link state). Ignore any other line; it's debug output.
- **Keep-alive over USB:** send `wdstream status` every 5 s. Over BLE, the connection itself counts as presence.

`firmware/tools/test_ble_link.sh` drives the BLE link from a Linux PC with BlueZ, which is handy for testing without a phone.

> **Security:** the BLE link is paired and encrypted (LE Secure Connections with a passkey). A phone that hasn't paired can connect at the radio level, but it can't read or write anything, and the rig drops it after 10 seconds. The code is only shown during a PAIR PHONE window. The `test:` developer commands only work over USB.
