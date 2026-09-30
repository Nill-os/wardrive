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
- **Service mode.** The CYD's log web server and over-the-air update are only up while a user has explicitly put the rig in service mode (and it times out after 10 minutes). Set `service_password` in `config.cfg` to require a password for both the OTA push and every web request; the file server is limited to `*.csv` in the session directory with path traversal rejected. **With no `service_password` set, service mode trusts everyone on the same WiFi** - anyone on that network can download the session logs (which contain locations) and, more seriously, flash firmware to the rig. Run it that way only on a network you trust, or set a password. A way to reach OTA or the logs without the configured password, or from off the local network, is in scope; the open-by-default behaviour when no password is set is a documented trade-off, not a vulnerability.

## Privacy, not a vulnerability

Collecting and uploading wireless observations is the whole point of the tool; that isn't a vulnerability. See [docs/CONFIG.md](docs/CONFIG.md#keep-it-private) for the `_nomap` and home-exclusion controls, and please use them.
