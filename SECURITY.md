# Security policy

## Reporting a vulnerability

If you find a security issue in the firmware, the Android app, or the upload path, please report it privately rather than opening a public issue:

- Use GitHub's **[private vulnerability reporting](https://github.com/Nill-os/wardrive/security/advisories/new)** (Security tab → Report a vulnerability), or
- open a normal issue that only says "security issue, please enable private reporting" without details, and the details can move to a private advisory.

Please don't include a working exploit or a step-by-step extraction path in anything public. A description of the class of problem and how to reproduce it is enough to get started.

## Scope

Most relevant here:

- **Credentials.** WiGLE / wdgwars API keys live only in `config.cfg` on the rig's SD card and in the phone's app-private storage. They are never committed, logged in the clear, or sent anywhere except the two upload endpoints. A path that leaks them is in scope.
- **The rig's BLE control link.** Commands (start/stop, upload, config, service mode) are only accepted from a paired, bonded phone. The USB-only `test:` commands (which can wipe logs) are rejected over BLE. A way to drive the rig without pairing is in scope.
- **Service mode.** The CYD's log web server and OTA are only up while a user has explicitly put the rig in service mode, and the file server is limited to `*.csv` in the session directory with path-traversal rejected. Issues here are in scope.

## Privacy, not a vulnerability

Collecting and uploading wireless observations is the whole point of the tool; that isn't a vulnerability. See [docs/CONFIG.md](docs/CONFIG.md#keep-it-private) for the `_nomap` and home-exclusion controls, and please use them.
