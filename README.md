# Wardrive Bridge

Android companion app for the [ESP32 wardriving rig](https://github.com/Nill-os/wardrive-esp32).

It links to the rig's CYD display board, adds the phone's own WiFi, Bluetooth and cell sightings, logs every run, and uploads to WiGLE and wdgwars.

## What it does

- **Rig link:** connects to the CYD board over BLE (primary), with USB-OTG serial as a fallback. Reconnects on its own after a drop.
- **Live dashboard:** WIGLE / WDGW / BT / CELL counts, run state, and the status of both links:
  - **CYD** (phone to CYD, shown as BLE or USB)
  - **RIG** (the CYD's wired link to the other boards)
- **Phone scanning:** WiFi, BLE and cell towers from the phone's own radios, merged with the rig's sightings and de-duplicated.
- **Detections:** trackers (AirTag/SmartTag), Flipper Zero, Flock cameras, skimmers, Remote ID drones, Meshtastic radios, and a few name-based heuristics. Every one is a best-effort pattern match on public signatures, not proof.
- **Logs:** per-run history, a field report grouped by category or maker, a map with heatmap, and CSV (WigleWifi-1.6) or GPX export.
- **Uploads:** WiGLE and wdgwars. A run only counts as uploaded once every configured service accepts it.
- **Privacy:** a home exclusion zone, MAC/SSID blacklists, and `_nomap` SSIDs are always dropped.
- **Android Auto:** a status screen for the car.

## Organic Maps overlay

A [fork of Organic Maps](https://github.com/Nill-os) can show this app's live status as an overlay on its map and in Android Auto, with a start/stop button in the car. It reads status from this app's `BridgeProvider` and never scans or talks to the rig itself, so the two apps don't compete for the rig. Only the Organic Maps build is allowed to read the provider; it's checked by package name and signing certificate.

## Build

```
./gradlew :app:assembleDebug
```

Needs JDK 17 and the Android SDK (minSdk 26, targetSdk 34). Put your SDK path in `local.properties`.
