# Phone app, map overlay and Android Auto

The rig works on its own, and **the phone is optional**. With the phone you get:

- a live dashboard and history
- the phone's own WiFi, BLE and cell scanning, merged in
- tracker and skimmer detection alerts
- start/stop from the phone, the map and the car screen

## Wardrive Bridge (Android)

Source: [wardrive-bridge](https://github.com/Nill-os/wardrive-bridge). It needs Android 8.0 or newer.

### Install

Build it yourself (a signed release APK may be added later):

```
git clone https://github.com/Nill-os/wardrive-bridge.git
cd wardrive-bridge
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17 and the Android SDK. Android Studio installs both; `local.properties` points Gradle at the SDK.

### First run

1. Grant **Location** ("Allow all the time" if you want it scanning with the screen off), **Nearby devices** (Bluetooth) and **Notifications**.
2. Open **Settings**:
   - **Upload credentials:** your WiGLE "Encoded for use" token and your wdgwars API key. These are only needed if the phone should upload its own logs.
   - **Home exclusion zone:** tap **USE CURRENT GPS FIX AS HOME** while at home, and set a radius (for example 300 m). Anything seen inside it is dropped completely.
   - **Blacklists:** any MACs or SSIDs you never want logged, such as your own devices.
   - Tap **SAVE**.
3. Tap **START** on the main screen. The app finds the rig over Bluetooth by itself: it looks for the `WardriveCYD` advertisement, so there is no pairing step.

The dashboard shows two link lines:

- `CYD: BLE` / `CYD: USB` / `CYD: DOWN`: the phone-to-CYD link.
- `RIG: UP` / `RIG: WAIT` / `RIG: DOWN`: the CYD's wired link to the scanner boards, as the CYD reports it.

### USB fallback

If Bluetooth isn't available, plug the phone into the CYD's USB port with a USB-OTG adapter and accept the USB permission prompt. The app switches back to BLE automatically when BLE returns.

### Privacy

- The home exclusion zone and blacklists apply to everything the **app** shows, logs and uploads, from both the rig and the phone.
- SSIDs ending in `_nomap` are always dropped.
- The rig's own SD logs and uploads **do not** use the app's exclusion zone. See [Config → Keep it private](CONFIG.md#keep-it-private).

## Organic Maps overlay (optional)

A lightly patched Organic Maps build shows a one-line Wardrive Bridge status on the map, for example:

```
ON · WIGLE 12 · WDGW 9 · BT 30 · CYD BLE · Rig ✓
```

Tap the overlay to open Wardrive Bridge. The overlay only **reads** status from Wardrive Bridge through a content provider; it never scans or talks to the rig itself, so the two apps can't conflict.

The provider only answers the approved Organic Maps build. It checks the caller's package name **and** the SHA-256 of its signing certificate (`ALLOWED` in `BridgeProvider.kt`). If you build Organic Maps yourself, put **your** certificate's SHA-256 there:

```
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

Use the hex digits in lowercase, without colons.

## Android Auto

Both apps show up on the car screen:

- **Wardrive Bridge** has its own car screen, with the live counts and link status.
- **Organic Maps** (the patched build) shows the same status line during navigation. The navigation action strip gets a **record** button that starts and stops the run.

The overlay text on the car map isn't tappable, because Android Auto doesn't allow taps on map drawings. Use the record button, or open Wardrive Bridge from the car launcher.

Sideloaded apps are hidden from Android Auto by default. To show them:

1. Open Android Auto's settings.
2. Tap the version number repeatedly until developer mode unlocks.
3. In the developer settings, enable **Unknown sources**.

## Protocol (for writing your own client)

The CYD speaks a line-based text protocol over BLE, and over USB serial at 115200 baud.

- **BLE:** the device is named `WardriveCYD`, with service `5b60de00-0000-4a6c-9b1a-6364796477b1`.
  - **Write** lines to characteristic `5b60de00-0001-4a6c-9b1a-6364796477b1`.
  - **Subscribe** to notifications on `5b60de00-0002-4a6c-9b1a-6364796477b1`.
  - Request a large MTU (the app uses 247). Lines are split to fit it.
- **Commands** are newline-terminated:
  - `wdstream start`, `wdstream stop` and `wdstream status`.
  - `scan start` and `scan stop` behave like tapping START/STOP.
- **Output:** lines prefixed with `WD:`, namely `WD:BEGIN`, `WD:AP,…`, `WD:BLE,…`, `WD:STATUS,…`, `WD:SCANSTATE:<0|1>` and `WD:MESHLINK:…` (rig link state). Ignore any other line; it's debug output.
- **Keep-alive over USB:** send `wdstream status` every 5 s. Over BLE, the connection itself counts as presence.

`tools/test_ble_link.sh` drives the BLE link from a Linux PC with BlueZ, which is handy for testing without a phone.

> **Security note:** the BLE link is open. Anyone within Bluetooth range can connect and start or stop scanning. They cannot read your config, and they cannot inject sightings into your logs.
