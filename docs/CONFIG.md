# config.cfg

The rig reads `config.cfg` from the root of **cyd_node's microSD card**. The card must be FAT32. wifi_node and ble_node have no SD card; cyd_node sends them what they need over the wire.

Start from [`firmware/config.cfg.example`](../firmware/config.cfg.example). Use one `key=value` per line, with no quotes and no spaces around `=`.

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
exclude_radius_m=0
retention_days=0
led_brightness=3
screen_brightness=100
screen_timeout_sec=0
led_color_ap=FF00FF
channel_hop_ms=150
```

| Key | Required | Meaning |
|---|---|---|
| `wifi_ssid` / `wifi_pass` | for uploads | The network cyd_node joins to upload. Usually your home WiFi. |
| `backup_wifi_ssid` / `backup_wifi_pass` | no | A second network, such as a phone hotspot. It is tried only if the first one can't be joined. |
| `wdgwars_api_key` | for uploads | From [wdgwars.pl/profile](https://wdgwars.pl/profile) → "Generate API key" (64 hex characters). wdgwars is the rig's main upload target: a file only counts as uploaded once wdgwars accepts it. |
| `wigle_api_token` | no | From [wigle.net/account](https://wigle.net/account). Paste the **"Encoded for use"** value, which is already base64 of `apiName:apiToken`. Leave it blank to skip WiGLE. WiGLE is best-effort: if it fails, the file is still marked as uploaded. |
| `min_upload_interval_sec` | no | Minimum gap between automatic uploads when **no** home geofence is set. Default 21600 (6 hours). Tapping UPLOAD ignores it. |
| `home_lat` / `home_lon` / `home_radius_m` | no | The home geofence for automatic "dock mode" uploads. When you arrive inside this circle with scanning stopped, the rig uploads once per arrival. Set `home_radius_m=0` or leave the keys out to disable it. |
| `exclude_radius_m` | no | Drop any sighting within this many metres of `home_lat`/`home_lon` before logging or uploading it — the rig's home exclusion zone. Default 0 (off). |
| `retention_days` | no | Delete **already-uploaded** session files older than this many days. The default, 0, keeps everything forever, which is the recommended setting. |
| `led_brightness` | no | Status LED brightness on all three boards, 0-100 %. Default 3 (dim, easy on the eyes at night). |
| `screen_brightness` | no | CYD screen backlight, 0-100 %. Default 100. |
| `screen_timeout_sec` | no | Blank the CYD screen after this many seconds without a touch. 0 = never (default). A touch wakes it. |
| `screen_keep_on_scanning` | no | Keep the screen on while a run is active (`true`, default), or let it time out anyway (`false`). |
| `led_color_ap` / `led_color_ble` / `led_color_ok` / `led_color_fail` | no | Status-LED colors on all three boards, hex `RRGGBB`. AP seen (default purple), BLE seen (cyan), ok/start/stop (green), error (red). |
| `channel_hop_ms` | no | How long each board dwells on a channel before hopping. Default 150 (just over one beacon interval). wifi_node hops 1/6/11; the CYD sweeps 1–11. |
| `service_password` | no | Password for [service mode](USING_THE_RIG.md#service-mode-download-logs-update-firmware) (over-the-air updates + the log web server). When set, the OTA push needs `--auth=<this>` and the web page/downloads need it too. Left blank, service mode is open to anyone on the same WiFi — see [SECURITY.md](../SECURITY.md). Recommended if you use service mode. |
| `alert_flock` / `alert_police` / `alert_skimmer` / `alert_flipper` / `alert_glasses` / `alert_actioncam` / `alert_pineapple` | no | Rig-side detection alerts: a red banner + LED flash on the CYD the first time each is seen in a run. `1`=on, `0`=off (default off). The phone app's DETECTION ALERTS toggles push these, so you normally set them there. |

## Changing these without the SD card

You don't have to pull the card to edit the WiFi networks or `service_password`:

- **On the rig:** CFG tab → **NETWORK & DEBUG** → tap a field to type a new value on the on-screen keyboard.
- **From the phone app:** Settings → **RIG NETWORK & DEBUG**.

Both apply the change live and save it back to `config.cfg`. Everything else is edited in the file.

## Keep it private

`config.cfg` holds your WiFi passwords, your upload keys and, through the geofence, where you live.

- It is listed in `.gitignore`. Never commit it, and never post a photo of it.
- The session CSVs on the card contain GPS coordinates of everywhere you drove, including the start and end of every trip. Treat them as location history.
- `home_radius_m` (the geofence) only controls **when uploads happen**. To keep sightings near home out of the logs entirely, set **`exclude_radius_m`** — the rig drops any sighting within that many metres of `home_lat`/`home_lon` before writing or uploading it. This is separate from the Nill OS - Wardriver app's own exclusion zone; set both if you use the phone too.
- SSIDs ending in `_nomap` or `_optout` are never logged (WiGLE's opt-out convention).

## Files the rig writes

Everything goes into a `wardrive/` folder on the card:

- `wifi_<n>.csv` and `ble_<n>.csv` are one pair per run, in WigleWifi-1.6 format.
- `<file>.csv.uploaded` is a marker written next to a file once wdgwars has accepted it. Delete the marker to force a re-upload.
