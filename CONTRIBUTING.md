# Contributing

Thanks for your interest in the Nill OS Wardriver. This is a hobby project shared under [MIT](LICENSE); contributions and issues are welcome.

## Reporting issues

Open a GitHub issue with:

- which part it's about - firmware (`wifi_node` / `ble_node` / `cyd_node`), the Android app, or the Organic Maps overlay;
- what you expected and what happened;
- for firmware, the serial log (`115200` baud) around the problem;
- your hardware (which ESP32-S3 boards, which CYD variant) and versions.

**Never paste raw logs, CSVs, or coordinates from a real drive** without scrubbing them first - the rig's heartbeat prints your live `lat`/`lon`, and session files are full of real MACs and locations. See [docs/CONFIG.md](docs/CONFIG.md#keep-it-private).

## Building

- **Firmware:** PlatformIO. `pio run -e wifi_node -t upload` (and `ble_node`, `cyd_node`). See [docs/BUILD_AND_FLASH.md](docs/BUILD_AND_FLASH.md).
- **App:** `cd android && ./gradlew :app:assembleDebug`.

## Pull requests

- Keep changes focused; one topic per PR.
- Match the surrounding style. The firmware and app both lean on thorough comments that explain *why* a non-obvious thing is the way it is - keep that up for anything subtle.
- Test on real hardware where you can, and say what you tested in the PR.
- Don't commit build output (`.pio/`, `android/app/build/`), API keys, or anything from a real drive.

## Scope and responsible use

This project only ever **listens** to broadcasts. Please don't send patches that make it connect to, deauth, or otherwise attack networks or devices - that's out of scope and won't be merged. Keep the `_nomap` opt-out and the home-exclusion features intact.
