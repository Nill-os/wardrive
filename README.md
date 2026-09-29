# Wardrive Bridge

Android companion app for the [ESP32 wardriving rig](https://github.com/Nill-os/wardrive-esp32).

It links to the rig's CYD display board, adds the phone's own WiFi, Bluetooth and cell sightings, logs every run, and uploads to WiGLE and wdgwars.

## What it does

- **Rig link:** connects to the CYD board over BLE (primary, paired and encrypted), with USB-OTG serial as a fallback. Reconnects on its own after a drop, and only ever to the rig it's paired with.
- **Live dashboard:** WIGLE / WDGW / BT / CELL counts, run state, and the status of both links:
  - **CYD** (phone to CYD, shown as BLE or USB)
  - **RIG** (the CYD's wired link to the other boards)
- **Phone scanning:** WiFi, BLE and cell towers from the phone's own radios, merged with the rig's sightings and de-duplicated.
- **Detections:** trackers (AirTag/SmartTag), Flipper Zero, Flock cameras, skimmers, Remote ID drones, Meshtastic radios, and a few name-based heuristics. Every one is a best-effort pattern match on public signatures, not proof.
- **Logs:** per-run history, a field report grouped by category or maker, a map with heatmap, and CSV (WigleWifi-1.6) or GPX export.
- **Uploads:** WiGLE and wdgwars. A run only counts as uploaded once every configured service accepts it.
- **Privacy:** a home exclusion zone, MAC/SSID blacklists, and `_nomap` SSIDs are always dropped. Sightings are only logged with a current GPS fix, never at 0,0. API keys are masked in Settings.
- **Android Auto:** a status screen for the car, including rig health.
- **Shortcuts:** a quick-settings tile and a home-screen widget to start and stop runs, plus a live notification with counts and a STOP button.
- **Rig health and control:** the dashboard shows the rig's GPS fix, satellites, SD card and runs waiting to upload. Tap it to make the rig upload now.
- **Exports:** a run as WigleWifi CSV, GPX or KML (Google Earth, coloured by security, with the route), or every run as one zip.
- **Hands-free:** optional spoken updates every few minutes, and a spoken warning when a tracker seems to be following you. This needs a text-to-speech engine on the phone.
- **Auto-upload:** optionally upload each run as soon as it stops, when you're on WiFi.

## Organic Maps overlay

A lightly patched Organic Maps build ([wardrive-maps-overlay](https://github.com/Nill-os/wardrive-maps-overlay)) can show this app's live status as an overlay on its map and in Android Auto, with a start/stop button in the car. It reads status from this app's `BridgeProvider` and never scans or talks to the rig itself, so the two apps don't compete for the rig. Only the Organic Maps build is allowed to read the provider; it's checked by package name and signing certificate.

To allow your own Organic Maps build, put its package name and the SHA-256 of its signing certificate in `ALLOWED` in `BridgeProvider.kt`.

## Build and install

```
git clone https://github.com/Nill-os/wardrive-bridge.git
cd wardrive-bridge
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and the Android SDK (minSdk 26 / Android 8.0, targetSdk 34). Android Studio installs both and writes `local.properties` for you.

## Setup

1. Grant **Location** ("Allow all the time" if it should keep scanning with the screen off), **Nearby devices** and **Notifications**.
2. In **Settings**:
   - Enter your WiGLE "Encoded for use" token and your wdgwars API key.
   - Set a home exclusion zone (**USE CURRENT GPS FIX AS HOME** plus a radius).
   - Add any MACs or SSIDs you never want logged.
3. **Pair with your rig (once):** on the rig's CFG tab tap **PAIR PHONE**, then type the 6-digit code it shows when Android asks. After that the app only ever connects to that rig, and the rig only accepts paired phones. If Bluetooth isn't available, plug into the CYD's USB port with an OTG adapter.
4. Tap **START**.
5. **Android Auto:** sideloaded apps are hidden until you enable Android Auto developer settings → **Unknown sources**.

The full guide is in the rig repo: [Phone app](https://github.com/Nill-os/wardrive-esp32/blob/master/docs/PHONE_APP.md).

## Privacy

Everything stays on the phone until you upload it. Exported CSV and GPX files contain the GPS track of every run, so treat them as location history.

## License

[MIT](LICENSE)
