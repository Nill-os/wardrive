# config.cfg

The rig reads `config.cfg` from the root of **cyd_node's microSD card**. The card must be FAT32. wifi_node and ble_node have no SD card; cyd_node sends them what they need over the wire.

Start from [`config.cfg.example`](../config.cfg.example). Use one `key=value` per line, with no quotes and no spaces around `=`.

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

| Key | Required | Meaning |
|---|---|---|
| `wifi_ssid` / `wifi_pass` | for uploads | The network cyd_node joins to upload. Usually your home WiFi. |
| `backup_wifi_ssid` / `backup_wifi_pass` | no | A second network, such as a phone hotspot. It is tried only if the first one can't be joined. |
| `wdgwars_api_key` | for uploads | From [wdgwars.pl/profile](https://wdgwars.pl/profile) → "Generate API key" (64 hex characters). wdgwars is the rig's main upload target: a file only counts as uploaded once wdgwars accepts it. |
| `wigle_api_token` | no | From [wigle.net/account](https://wigle.net/account). Paste the **"Encoded for use"** value, which is already base64 of `apiName:apiToken`. Leave it blank to skip WiGLE. WiGLE is best-effort: if it fails, the file is still marked as uploaded. |
| `min_upload_interval_sec` | no | Minimum gap between automatic uploads when **no** home geofence is set. Default 21600 (6 hours). Tapping UPLOAD ignores it. |
| `home_lat` / `home_lon` / `home_radius_m` | no | The home geofence for automatic "dock mode" uploads. When you arrive inside this circle with scanning stopped, the rig uploads once per arrival. Set `home_radius_m=0` or leave the keys out to disable it. |
| `retention_days` | no | Delete **already-uploaded** session files older than this many days. The default, 0, keeps everything forever, which is the recommended setting. |
| `channel_hop_ms` | no | How long wifi_node listens on each 2.4 GHz channel before moving on. Default 150. Lower values catch more APs at speed; higher values catch more quiet devices. |

## Keep it private

`config.cfg` holds your WiFi passwords, your upload keys and, through the geofence, where you live.

- It is listed in `.gitignore`. Never commit it, and never post a photo of it.
- The session CSVs on the card contain GPS coordinates of everywhere you drove, including the start and end of every trip. Treat them as location history.
- The rig's geofence only controls **when uploads happen**. It does **not** remove sightings near home from the logs. To drop sightings near home before they are shown or uploaded, use the Wardrive Bridge app's home exclusion zone (see [Phone app](PHONE_APP.md#privacy)), or trim the CSVs before uploading them anywhere.

## Files the rig writes

Everything goes into a `wardrive/` folder on the card:

- `wifi_<n>.csv` and `ble_<n>.csv` are one pair per run, in WigleWifi-1.6 format.
- `<file>.csv.uploaded` is a marker written next to a file once wdgwars has accepted it. Delete the marker to force a re-upload.
